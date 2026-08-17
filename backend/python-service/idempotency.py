"""
Idempotency helpers backed by the `processed_events` table.

Using the DB (rather than only an in-memory set) means idempotency
survives a pod restart / rolling deploy / crash — any consumer
instance can check whether an event was already handled.
"""

import uuid


async def already_processed(pool, event_id: str) -> bool:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT 1 FROM processed_events WHERE event_id = $1", uuid.UUID(event_id)
        )
        return row is not None


async def mark_processed(conn, event_id: str, topic: str) -> None:
    # Called inside the same transaction as the business write, using
    # ON CONFLICT DO NOTHING in case of a rare race between two
    # consumer instances that both picked up a redelivered message.
    await conn.execute(
        """INSERT INTO processed_events (event_id, topic)
           VALUES ($1, $2)
           ON CONFLICT (event_id) DO NOTHING""",
        uuid.UUID(event_id), topic,
    )
