# TaskFlow — Real-Time Distributed Task-Management System

Java backend + Python AI service + static frontend, communicating through Kafka, backed by PostgreSQL and Redis.

```
taskflow/
├── frontend/          Static HTML/CSS/JS client (no build step)
├── backend/
│   ├── java-backend/  Spring Boot: REST API, auth/RBAC, concurrency, outbox
│   └── python-service/ FastAPI: Kafka consumer, priority prediction
├── database/
│   ├── 01_schema.sql              Full DDL — run this first
│   └── 02_seed_and_queries.sql    Demo data + example runnable queries
├── deployment/
│   ├── docker-compose.yml         Local: Postgres, Redis, Kafka, kafka-exporter, both services, Prometheus, Grafana
│   ├── .env.example               Copy to .env and change before any non-local use (no secrets are committed)
│   ├── grafana/provisioning/      Auto-loads the Prometheus datasource + overview dashboard on Grafana startup
│   └── k8s-manifests.yaml         Production Kubernetes: app Deployments/Services/HPA/KEDA + backing Postgres/Redis/Kafka + Secret/ConfigMap
├── tests/
│   ├── unit/          JUnit5 + pytest
│   ├── integration/   Idempotent-replay test (Testcontainers/pytest)
│   └── load/          k6 script, 10k concurrent virtual users
└── docs/               Architecture PDF, API docs, DB design doc, test report, deployment guide
```

---

## 1. Run the database (PostgreSQL)

```bash
# create the database
createdb taskmgmt

# apply schema, then seed demo data + try the example queries
psql -d taskmgmt -f database/01_schema.sql
psql -d taskmgmt -f database/02_seed_and_queries.sql
```

Or, everything together via Docker:
```bash
docker run --name taskmgmt-pg -e POSTGRES_DB=taskmgmt -e POSTGRES_USER=taskmgmt -e POSTGRES_PASSWORD=taskmgmt -p 5432:5432 -d postgres:15
psql -h localhost -U taskmgmt -d taskmgmt -f database/01_schema.sql
psql -h localhost -U taskmgmt -d taskmgmt -f database/02_seed_and_queries.sql
```

## 2. Run everything (Postgres + Redis + Kafka + both services)

```bash
docker-compose -f deployment/docker-compose.yml up -d
```
- Java backend → `http://localhost:8080`
- Python AI service → `http://localhost:8000`
- Prometheus → `http://localhost:9090`, Grafana → `http://localhost:3000`

## 3. Run the frontend

No build step — it's static HTML/CSS/JS.

```bash
cd frontend
python3 -m http.server 8123
# open http://localhost:8123
```

On the login screen, set **API base URL** to your running Java backend (default `http://localhost:8080/api/v1`) and sign in.

**Note:** if the backend isn't running, the frontend automatically falls back to an in-memory demo dataset so the UI itself can still be reviewed — a banner makes this explicit rather than silently faking data.

## 4. Run tests

```bash
# Java unit tests
cd backend/java-backend && mvn test

# Python unit tests
cd backend/python-service && pytest ../../tests/unit

# Integration tests (needs Postgres + Kafka up)
docker-compose -f deployment/docker-compose.yml up -d postgres kafka
pytest tests/integration -m integration

# Load test (needs a running staging/local backend)
k6 run --vus 10000 --duration 5m tests/load/load_test.js
```

## 5. Documentation

See `docs/`:
- `01-System-Architecture-and-Explanation.pdf`
- `02-API-Documentation.md`
- `03-Database-Schema-and-Design.md`
- `04-Test-Cases-and-Reports.md`
- `05-Deployment-Guide.md`

## 6. Proof of a working system

See `screenshots/` — live captures of the full stack running end-to-end
(health checks, Prometheus scraping, and the frontend showing tasks with
AI-predicted priorities flowing back from the Python service through
Kafka). See `screenshots/README.md` for what each one demonstrates.
