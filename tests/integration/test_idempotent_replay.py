"""
Integration test (uses docker-compose Postgres via a real connection
pool / testcontainers-python in a full setup). Demonstrates the core
guarantee from Requirement 6: replaying the SAME Kafka event twice
must not produce two prediction rows.

Run with: pytest tests/integration -m integration
Requires: docker-compose -f deployment/docker-compose.yml up -d postgres
"""

import asyncio
import json
import uuid
import pytest

pytestmark = pytest.mark.integration


@pytest.mark.asyncio
async def test_replaying_same_event_id_does_not_duplicate(pg_pool, kafka_producer_stub):
    import sys, os
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "python-service"))
    from main import process_message

    event_id = str(uuid.uuid4())
    event = {
        "eventId": event_id,
        "correlationId": str(uuid.uuid4()),
        "taskId": str(uuid.uuid4()),
        "title": "Fix urgent security bug",
        "description": "critical vulnerability",
        "dueDate": None,
    }
    raw = json.dumps(event).encode()

    # process the same message twice, simulating a Kafka redelivery
    # after a crash right before the offset commit
    await process_message(kafka_producer_stub, pg_pool, raw, {})
    await process_message(kafka_producer_stub, pg_pool, raw, {})

    async with pg_pool.acquire() as conn:
        count = await conn.fetchval(
            "SELECT count(*) FROM task_priority_predictions WHERE event_id = $1",
            uuid.UUID(event_id),
        )
    assert count == 1, "duplicate processing occurred for the same eventId"
