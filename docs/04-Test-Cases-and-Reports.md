# Test Cases & Reports
**Real-Time Distributed Task-Management System — Fardeen, Pakistan**

Full test source: `tests/unit/`, `tests/integration/`, `tests/load/` in the source-code package.

---

## 1. Unit Tests

| # | Test | File | Verifies |
|---|---|---|---|
| U1 | `updateTaskStatus_conflictingVersion_throws409EquivalentException` | `tests/unit/TaskServiceTest.java` | Stale `version` on update is rejected instead of silently overwriting |
| U2 | `createTask_writesOutboxRowInSameTransaction` | `tests/unit/TaskServiceTest.java` | Every task creation writes exactly one outbox event in the same transaction |
| U3 | `test_urgent_keyword_and_close_due_date_is_critical` | `tests/unit/test_priority_model.py` | Priority model correctly flags urgent + near-due tasks as `CRITICAL` |
| U4 | `test_far_due_date_no_keywords_is_low` | `tests/unit/test_priority_model.py` | Low-urgency tasks are scored `LOW` |
| U5 | `test_no_due_date_no_keywords_defaults_low` | `tests/unit/test_priority_model.py` | Missing due date doesn't crash the model; safe default applied |

**Tooling:** JUnit5 + Mockito (Java), pytest (Python). Run via `mvn test` / `pytest tests/unit`.

## 2. Integration Tests

| # | Test | File | Verifies |
|---|---|---|---|
| I1 | `test_replaying_same_event_id_does_not_duplicate` | `tests/integration/test_idempotent_replay.py` | Redelivering the same Kafka `task.created` event twice (simulating a crash-before-offset-commit) produces exactly one `task_priority_predictions` row — core proof for Requirement 6 |

**Tooling:** Testcontainers (real Postgres + Kafka in Docker) for the Java side; `pytest` against a docker-compose Postgres for the Python side. Run via `docker-compose -f deployment/docker-compose.yml up -d postgres kafka && pytest tests/integration -m integration`.

**Recommended additions for a full suite:** end-to-end outbox→Kafka→consumer→callback round trip; RBAC denial tests (a `MEMBER` cannot call `/tasks/reassign`); optimistic-lock conflict returns `409` over real HTTP, not just at the service layer.

## 3. Load Test

**File:** `tests/load/load_test.js` (k6)

**Scenario:** ramp 0 → 2,000 → 10,000 virtual users over ~4 minutes, hold 10,000 for 5 minutes, ramp down. Each iteration: create a task, retry the *same* request with the *same* `Idempotency-Key` (proving no duplicate is created), then read it back.

**Pass/fail thresholds:**
| Metric | Threshold |
|---|---|
| `http_req_duration` p95 | < 500 ms |
| `http_req_duration` p99 | < 1000 ms |
| `http_req_failed` rate | < 1% |
| `duplicate_task_creation` counter | 0 |

**Run:** `k6 run --vus 10000 --duration 5m tests/load/load_test.js` against a staging environment with `BASE_URL` and `AUTH_TOKEN` env vars set.

> Note: this document ships the test scripts and the thresholds they must pass; actual numeric run results (latency graphs, pass/fail output) will come from executing this suite against a deployed staging environment, which is outside the scope of this take-home exercise. Screenshots of a k6/Grafana run can be attached here once available.

## 4. Coverage Summary

| Requirement | Covered by |
|---|---|
| No lost/duplicate tasks under load (Req 5) | I1, load test duplicate-counter |
| Python crash-safe retry (Req 6) | I1 |
| Race conditions / deadlocks (Req 7) | U1 (optimistic lock), load test (concurrent claim contention) |
| 10,000+ concurrent users (Req 5) | Load test |
