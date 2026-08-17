-- =========================================================
-- 02_seed_and_queries.sql
-- Run AFTER 01_schema.sql. Seed data + example queries you
-- can run directly in psql/pgAdmin against the `taskmgmt` DB.
-- =========================================================

-- ---------- Seed lookup data ----------
INSERT INTO roles (name) VALUES ('ADMIN'), ('MANAGER'), ('MEMBER')
ON CONFLICT (name) DO NOTHING;

-- ---------- Seed users ----------
-- password_hash below is a verified bcrypt hash of "demo1234" (cost 10) — for local/demo
-- use only. Previously this was a hand-typed placeholder that LOOKED like a bcrypt hash
-- (correct length/format) but did not actually verify against "demo1234" -- login with
-- any seeded demo account, including the one pre-filled on the frontend login screen,
-- would fail with 401 no matter what password was typed.
INSERT INTO users (id, email, password_hash, role_id)
VALUES
  ('11111111-1111-4111-8111-111111111111', 'admin@taskflow.dev',   '$2b$10$vXWTdYRDvSdFPE.LtNYTt.RshVsujawAoGqYoC4RAq3DQ5SLwyBXm', 1),
  ('22222222-2222-4222-8222-222222222222', 'manager@taskflow.dev', '$2b$10$vXWTdYRDvSdFPE.LtNYTt.RshVsujawAoGqYoC4RAq3DQ5SLwyBXm', 2),
  ('33333333-3333-4333-8333-333333333333', 'demo@taskflow.dev',    '$2b$10$vXWTdYRDvSdFPE.LtNYTt.RshVsujawAoGqYoC4RAq3DQ5SLwyBXm', 3)
ON CONFLICT (email) DO NOTHING;

-- ---------- Seed tasks ----------
INSERT INTO tasks (id, title, description, status, priority, priority_confidence, created_by, due_date)
VALUES
  (gen_random_uuid(), 'Fix login bug on Safari', 'URGENT — users cannot authenticate on Safari 18', 'OPEN', 'CRITICAL', 0.93, '33333333-3333-4333-8333-333333333333', now() + interval '2 hours'),
  (gen_random_uuid(), 'Write onboarding email copy', 'Draft the first welcome email for new signups', 'OPEN', 'LOW', 0.60, '33333333-3333-4333-8333-333333333333', now() + interval '20 days'),
  (gen_random_uuid(), 'Add retry/backoff to Python consumer', 'Kafka consumer should retry transient failures before DLQ', 'IN_PROGRESS', 'HIGH', 0.82, '22222222-2222-4222-8222-222222222222', now() + interval '20 hours'),
  (gen_random_uuid(), 'Set up Grafana dashboards', 'Consumer lag, p95 latency, DB pool utilization', 'OPEN', 'MEDIUM', 0.70, '22222222-2222-4222-8222-222222222222', now() + interval '2 days');

-- =========================================================
-- Example queries — safe to run directly in psql
-- =========================================================

-- 1) All open tasks ordered by predicted priority (highest first)
SELECT id, title, status, priority, priority_confidence, due_date
FROM tasks
WHERE status = 'OPEN'
ORDER BY
  CASE priority
    WHEN 'CRITICAL' THEN 1
    WHEN 'HIGH'     THEN 2
    WHEN 'MEDIUM'   THEN 3
    WHEN 'LOW'      THEN 4
    ELSE 5
  END,
  due_date NULLS LAST;

-- 2) Atomically claim the next open task (race-condition-safe)
--    Run this exact statement from the application inside a transaction.
-- BEGIN;
SELECT id, title FROM tasks
WHERE status = 'OPEN'
ORDER BY created_at
FOR UPDATE SKIP LOCKED
LIMIT 1;
-- UPDATE tasks SET status = 'IN_PROGRESS', version = version + 1 WHERE id = :claimed_id;
-- COMMIT;

-- 3) Tasks per user with role
SELECT u.email, u.role_id, r.name AS role, COUNT(ta.task_id) AS assigned_tasks
FROM users u
JOIN roles r ON r.id = u.role_id
LEFT JOIN task_assignees ta ON ta.user_id = u.id
GROUP BY u.email, u.role_id, r.name
ORDER BY assigned_tasks DESC;

-- 4) Kafka events not yet published by the outbox relay (should normally be near-empty)
SELECT id, event_type, aggregate_id, created_at
FROM outbox_events
WHERE published_at IS NULL
ORDER BY created_at
LIMIT 50;

-- 5) Verify idempotent processing: an eventId should appear at most once
SELECT event_id, COUNT(*) 
FROM processed_events
GROUP BY event_id
HAVING COUNT(*) > 1;   -- should always return zero rows

-- 6) Priority distribution across all tasks (useful for a dashboard)
SELECT priority, COUNT(*) AS task_count
FROM tasks
WHERE priority IS NOT NULL
GROUP BY priority
ORDER BY task_count DESC;
