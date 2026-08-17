-- =========================================================
-- Real-Time Distributed Task-Management System — Schema
-- PostgreSQL 15+
-- =========================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- required for gen_random_uuid() below

CREATE TABLE IF NOT EXISTS roles (
    id          SMALLSERIAL PRIMARY KEY,
    name        VARCHAR(20) UNIQUE NOT NULL   -- ADMIN, MANAGER, MEMBER
);

CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role_id         SMALLINT NOT NULL REFERENCES roles(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- OPEN, IN_PROGRESS, DONE, CANCELLED
    priority        VARCHAR(10),                            -- LOW/MEDIUM/HIGH/CRITICAL, filled by AI service
    priority_confidence NUMERIC(4,3),
    created_by      UUID NOT NULL REFERENCES users(id),
    due_date        TIMESTAMPTZ,
    version         INTEGER NOT NULL DEFAULT 0,             -- optimistic locking
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_priority ON tasks(priority);
CREATE INDEX IF NOT EXISTS idx_tasks_due_date ON tasks(due_date);

CREATE TABLE IF NOT EXISTS task_assignees (
    task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, user_id)
);

-- Idempotency for inbound API requests (e.g. task creation retried by a flaky client)
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id),
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transactional outbox: guarantees a Kafka event is eventually published
-- exactly once for every committed DB change that needs one.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL DEFAULT gen_random_uuid(),  -- what the Python consumer's processed_events table keys off
    aggregate_type  VARCHAR(50) NOT NULL,      -- e.g. 'TASK'
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(50) NOT NULL,      -- 'task.created', 'task.updated'
    payload         JSONB NOT NULL,
    correlation_id  UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ                -- NULL until the relay confirms publish
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events(published_at) WHERE published_at IS NULL;

-- Idempotency for the Python consumer: has this Kafka event already been applied?
CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID PRIMARY KEY,
    topic           VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS task_priority_predictions (
    id              BIGSERIAL PRIMARY KEY,
    task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    predicted_priority VARCHAR(10) NOT NULL,
    confidence      NUMERIC(4,3) NOT NULL,
    model_version   VARCHAR(30) NOT NULL,
    event_id        UUID NOT NULL,             -- ties back to processed_events for traceability
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       UUID NOT NULL,
    action          VARCHAR(50) NOT NULL,
    actor_id        UUID REFERENCES users(id),
    detail          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================
-- Race-condition-safe "claim" query used by the API layer.
-- SKIP LOCKED lets many users attempt to claim tasks
-- concurrently without blocking on each other; the row
-- already locked by another transaction is simply skipped.
-- =========================================================
-- SELECT id FROM tasks
-- WHERE status = 'OPEN'
-- ORDER BY created_at
-- FOR UPDATE SKIP LOCKED
-- LIMIT 1;

-- =========================================================
-- Deadlock avoidance note (see design doc R7): whenever a
-- transaction must lock multiple task rows (e.g. bulk
-- reassignment), always order the lock acquisition by id:
-- SELECT * FROM tasks WHERE id = ANY($1) ORDER BY id FOR UPDATE;
-- =========================================================
