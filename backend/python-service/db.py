"""
Postgres connection pool for the AI service.

A single pooled connection set is created once at startup and reused
by every message handled in main.py (process_message) and by the
idempotency checks in idempotency.py. Pooling matters here because
under load the service is doing one short transaction per Kafka
message, and opening a fresh connection per message would fall over
long before the 10,000+ concurrent user target.
"""

import os
import logging

import asyncpg

log = logging.getLogger("ai-service.db")

DB_HOST = os.getenv("DB_HOST", "postgres")
DB_PORT = int(os.getenv("DB_PORT", "5432"))
DB_NAME = os.getenv("DB_NAME", "taskmgmt")
DB_USER = os.getenv("DB_USER", "taskmgmt")
DB_PASSWORD = os.getenv("DB_PASSWORD", "taskmgmt_dev_only")

_pool: asyncpg.Pool | None = None


async def get_pool() -> asyncpg.Pool:
    """Lazily create the pool once, then hand back the same instance
    on every subsequent call (consume_loop calls this once at startup,
    but keeping it idempotent makes it safe to call from anywhere,
    e.g. tests or the FastAPI health check)."""
    global _pool
    if _pool is None:
        log.info("creating asyncpg pool -> %s:%s/%s", DB_HOST, DB_PORT, DB_NAME)
        _pool = await asyncpg.create_pool(
            host=DB_HOST,
            port=DB_PORT,
            database=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
            min_size=5,
            max_size=20,          # bounded, same reasoning as the Java side's Hikari pool
            command_timeout=10,
        )
    return _pool


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None
