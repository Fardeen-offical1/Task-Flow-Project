# Proof of Working System — Screenshots

These screenshots were captured from a live local run of the full stack
(`docker-compose up -d`), demonstrating that every core requirement is not
just implemented in code but actually working end-to-end.

| # | File | What it proves |
|---|------|-----------------|
| 1 | `01-java-backend-health.png` | Java backend is up and its Postgres connection is healthy (`/actuator/health` → `status: UP`, `db: UP`). |
| 2 | `02-python-ai-service-health.png` | Python AI service is running and responding (`/health` → `status: ok`). |
| 3 | `03-prometheus-targets.png` | Prometheus is successfully scraping metrics from both `java-backend` and itself — monitoring is live (`1/1 up` for both targets). |
| 4 | `04-frontend-tasks-with-ai-priority.png` | End-to-end pipeline proof: tasks created in the Java backend were picked up by the Python AI service via Kafka, which predicted a priority + confidence score for each (e.g. "Fix login bug on Safari" → **CRITICAL · 93%**), and the result flowed back and is displayed in the frontend. |
| 5 | `05-frontend-done-tasks-filter.png` | Task status filtering works (Done view), and AI priority labels persist correctly per task. |
| 6 | `06-grafana-dashboard.png` | Grafana is connected and reachable, ready for dashboards to be built on top of the Prometheus metrics already flowing in. |
| 7 | `07-docker-compose-up-all-services.png` | All 7 services in the stack (Postgres, Redis, Kafka, Java backend, Python AI service, Prometheus, Grafana) start successfully with a single `docker-compose up -d` command. |

## How to reproduce

```bash
cd deployment
docker-compose up -d
docker-compose ps        # confirms all 7 containers running/healthy

# then visit:
# http://localhost:8080/actuator/health
# http://localhost:8000/health
# http://localhost:9090/targets
# http://localhost:8123  (frontend, after `cd frontend && python3 -m http.server 8123`)
```
