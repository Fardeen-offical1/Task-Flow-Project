# Database Schema & Design Document
**Real-Time Distributed Task-Management System — Fardeen, Pakistan**

---

## 1. Design Goals

The schema must support: safe concurrent updates from 10,000+ users, zero task loss/duplication across a Java→Kafka→Python round trip, and a clear audit trail — without holding long-lived locks that could cause deadlocks.

## 2. Entity-Relationship Summary

```
users ──< task_assignees >── tasks ──< task_priority_predictions
  │                              │
  │                              └──< audit_log
  └── roles (lookup)

outbox_events        (Java-side reliable publish)
processed_events     (Python-side idempotent consume)
idempotency_keys      (API-level duplicate request protection)
```

## 3. Table Reference

| Table | Purpose |
|---|---|
| `users` | Account + role (ADMIN/MANAGER/MEMBER) |
| `roles` | Lookup table for RBAC roles |
| `tasks` | Core task record; `version` column drives optimistic locking |
| `task_assignees` | Many-to-many users↔tasks |
| `idempotency_keys` | Prevents duplicate task creation from client retries |
| `outbox_events` | Transactional outbox — guarantees a committed DB change is eventually published to Kafka exactly once |
| `processed_events` | Records which Kafka `eventId`s the Python service already applied — makes consumption idempotent |
| `task_priority_predictions` | AI-predicted priority + confidence + model version per task |
| `audit_log` | Who did what, when |

Full DDL: see `schema.sql` in the source code package.

## 4. Concurrency Design

### 4.1 Race conditions
- **Optimistic locking**: every `UPDATE` on `tasks` increments `version`; a stale writer gets rejected (surfaced as HTTP 409) instead of silently overwriting another user's change.
- **Contended claims**: `SELECT id FROM tasks WHERE status='OPEN' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1` — many users can attempt to claim simultaneously; each gets a different row (or none), with zero blocking between them.

### 4.2 Deadlocks
- Multi-row transactions (e.g., bulk reassignment) always lock rows in **ascending `id` order**, so two concurrent transactions can never form a lock cycle.
- Transactions are kept short: no HTTP/Kafka calls happen while a row lock is held — the Kafka publish happens *after* commit, via the outbox relay reading `outbox_events`.
- `lock_timeout` and `statement_timeout` are set at the connection/session level so a stuck transaction fails fast (and is retried) rather than blocking others indefinitely.

## 5. Why a transactional outbox instead of a direct Kafka publish?

Publishing to Kafka directly inside the same request that writes to Postgres creates a split-brain risk: the DB commit could succeed while the Kafka publish fails (or vice-versa), silently losing or duplicating a task-created event. Writing an `outbox_events` row in the *same* transaction as the task write, and having a separate relay process publish it, means the two operations are atomic from the caller's point of view — the event will always eventually be published if (and only if) the task write committed.

## 6. Why idempotency on the consumer side too?

Kafka guarantees **at-least-once** delivery, not exactly-once. A rebalance or a crash right before an offset commit can cause the same message to be redelivered. The `processed_events` table (checked before doing any work, written in the same transaction as the business write) makes redelivery a safe no-op rather than a duplicate priority prediction.
