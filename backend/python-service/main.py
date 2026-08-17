"""
Python AI/data-processing service.

Consumes `task.created` events from Kafka, predicts a priority for
each task, and publishes the result to `task.priority.updated`.
Exposes a small FastAPI app for health checks / manual re-scoring.

Key reliability properties (Requirement 6):
  - Idempotent: every event carries an eventId; we record processed
    ids in Postgres (or Redis) BEFORE committing the Kafka offset, so
    re-delivery after a crash never double-processes.
  - Manual offset commit: offset is only committed after the result
    is durably written and the event is marked processed.
  - Retry with backoff + DLQ: transient errors are retried a few
    times with exponential backoff before landing in a DLQ topic.
"""

import asyncio
import json
import logging
import os
import time
import uuid
from datetime import datetime, timezone

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI
from fastapi.responses import PlainTextResponse
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST

from priority_model import predict_priority
from idempotency import already_processed, mark_processed
from db import get_pool

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ai-service")

# Read from env (matches docker-compose / k8s ConfigMap), falling back to
# the docker-compose service name for local dev. Previously this was a
# hardcoded "kafka:9092" literal that silently ignored the KAFKA_BOOTSTRAP
# env var both docker-compose and the k8s manifests already pass in —
# harmless in docker-compose only because the hostname happened to match,
# but it would break the moment the k8s Service name differed.
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP", "kafka:9092")
TOPIC_IN = "task.created"
TOPIC_OUT = "task.priority.updated"
TOPIC_DLQ = "task.created.DLQ"
CONSUMER_GROUP = "ai-priority-service"
MAX_RETRIES = 3
BACKOFF_BASE_SECONDS = 1

app = FastAPI(title="Task Priority AI Service")

# --- Prometheus metrics (Requirement: monitor failures in production) ---
# Previously this service exposed no /metrics at all, so Prometheus could
# only scrape the Java side; Python-side throughput/failures/DLQ volume
# were invisible.
EVENTS_PROCESSED = Counter("ai_events_processed_total", "Events successfully processed")
EVENTS_DLQ = Counter("ai_events_dlq_total", "Events sent to the dead-letter queue")
EVENTS_SKIPPED_DUPLICATE = Counter("ai_events_skipped_duplicate_total", "Events skipped as already-processed")
PROCESS_LATENCY = Histogram("ai_event_process_seconds", "Time to process one event end-to-end")


@app.get("/health")
async def health():
    return {"status": "ok", "time": datetime.now(timezone.utc).isoformat()}


@app.get("/metrics")
async def metrics():
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)


async def process_message(producer: AIOKafkaProducer, pool, raw_value: bytes, headers: dict) -> None:
    event = json.loads(raw_value)
    event_id = event["eventId"]
    correlation_id = event.get("correlationId", str(uuid.uuid4()))

    # --- Idempotency check ---------------------------------------
    if await already_processed(pool, event_id):
        log.info("event %s already processed, skipping (correlation=%s)", event_id, correlation_id)
        EVENTS_SKIPPED_DUPLICATE.inc()
        return

    task_id = event["taskId"]
    title = event.get("title", "")
    description = event.get("description", "")
    due_date = event.get("dueDate")

    priority, confidence = predict_priority(title, description, due_date)

    result = {
        "eventId": str(uuid.uuid4()),
        "sourceEventId": event_id,
        "correlationId": correlation_id,
        "taskId": task_id,
        "priority": priority,
        "confidence": confidence,
        "modelVersion": "v1.2.0",
    }

    # Write result + mark processed atomically in a single DB transaction,
    # THEN commit the Kafka offset (handled by caller after this returns).
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                """INSERT INTO task_priority_predictions
                   (task_id, predicted_priority, confidence, model_version, event_id)
                   VALUES ($1, $2, $3, $4, $5)""",
                task_id, priority, confidence, "v1.2.0", uuid.UUID(event_id),
            )
            await mark_processed(conn, event_id, TOPIC_IN)

    await producer.send_and_wait(TOPIC_OUT, json.dumps(result).encode("utf-8"), key=task_id.encode())
    log.info("processed task %s -> priority=%s (correlation=%s)", task_id, priority, correlation_id)
    EVENTS_PROCESSED.inc()


async def process_with_retry(producer, pool, raw_value: bytes, headers: dict) -> bool:
    """Returns True if the message was handled (success or sent to DLQ),
    False if it should be retried by redelivery (offset not committed)."""
    start = time.monotonic()
    try:
        for attempt in range(1, MAX_RETRIES + 1):
            try:
                await process_message(producer, pool, raw_value, headers)
                return True
            except Exception as e:
                log.warning("attempt %s/%s failed: %s", attempt, MAX_RETRIES, e)
                if attempt < MAX_RETRIES:
                    await asyncio.sleep(BACKOFF_BASE_SECONDS * (2 ** (attempt - 1)))
                else:
                    # Exhausted retries -> dead-letter it instead of blocking
                    # the partition forever, and alert (via metrics/log scrape).
                    await producer.send_and_wait(TOPIC_DLQ, raw_value)
                    EVENTS_DLQ.inc()
                    log.error("event moved to DLQ after %s attempts", MAX_RETRIES)
                    return True
        return False
    finally:
        PROCESS_LATENCY.observe(time.monotonic() - start)


async def consume_loop():
    pool = await get_pool()
    consumer = AIOKafkaConsumer(
        TOPIC_IN,
        bootstrap_servers=KAFKA_BOOTSTRAP,
        group_id=CONSUMER_GROUP,
        enable_auto_commit=False,           # manual commit -> Requirement 6
        auto_offset_reset="earliest",
    )
    producer = AIOKafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP,
        enable_idempotence=True,            # producer-side dedup on retry
        acks="all",
    )
    await consumer.start()
    await producer.start()
    log.info("consumer started on topic=%s group=%s", TOPIC_IN, CONSUMER_GROUP)

    try:
        async for msg in consumer:
            handled = await process_with_retry(producer, pool, msg.value, dict(msg.headers or []))
            if handled:
                await consumer.commit()  # only advance offset once durably processed
    finally:
        await consumer.stop()
        await producer.stop()


@app.on_event("startup")
async def startup_event():
    asyncio.create_task(consume_loop())
