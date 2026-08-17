# Deployment Guide
**Real-Time Distributed Task-Management System — Fardeen, Pakistan**

---

## 1. Local Development

```bash
docker-compose -f deployment/docker-compose.yml up -d
```
Brings up: Postgres, Redis, Kafka, a Kafka exporter (for consumer-lag
metrics), the Java backend (`:8080`), the Python AI service (`:8000`),
Prometheus (`:9090`), and Grafana (`:3000`, dashboards auto-provisioned).

**Schema**: Postgres' entrypoint automatically runs everything in
`deployment/init/` (`01_schema.sql`, then `02_seed_and_queries.sql`) the
first time the `pgdata` volume is created — no separate migration step
needed for local dev. `database/01_schema.sql` is kept identical to
`deployment/init/01_schema.sql` so it's copy-pasteable for a manual
`psql` run (see README.md) without a running container.

> This project does not currently use a migration tool (Flyway/Liquibase).
> For a real production rollout you'd want one — schema changes then ship
> as versioned, one-way-forward migration files instead of a single SQL
> script — but that's future work, not part of this deliverable. Track it
> as a known gap rather than assuming it's wired in.

## 2. Production Deployment (Kubernetes)

1. **CI build** (GitHub Actions): run unit + integration tests → build Docker images for `java-backend` and `python-ai-service` → push to the registry, tagged with the commit SHA.
2. **Pre-deploy schema step**: apply `database/01_schema.sql` (idempotent — `CREATE TABLE IF NOT EXISTS`-style guards) via a Kubernetes `Job` before rolling out new pods. Swap this for a real migration tool (Flyway/Liquibase, see note above) before this system carries production data.
3. **Apply manifests**:
   ```bash
   kubectl apply -f deployment/k8s-manifests.yaml
   ```
   This creates Deployments for both services, a CPU-based `HorizontalPodAutoscaler` for the Java backend, and a Kafka-lag-based `KEDA ScaledObject` for the Python service.
4. **Rolling update strategy**: `maxSurge=1, maxUnavailable=0` — new pods must pass their readiness probe (`/actuator/health/readiness` for Java, `/health` for Python) before old pods are terminated, so deploys are zero-downtime.
5. **Secrets/config**: DB credentials, JWT signing keys, and Kafka bootstrap servers are injected via Kubernetes `Secret`/`ConfigMap`, never baked into images.

## 3. Independent Scaling (Bonus Requirement)

- **Java backend**: scales on CPU utilization (target 65%), 3–50 replicas.
- **Python AI service**: scales on **Kafka consumer lag** on the `task.created` topic (target lag threshold: 50 messages per partition), 3–30 replicas. This matters because the Python workload is I/O/queue-bound, not CPU-bound — CPU-based scaling alone would under-provision during traffic spikes.
- Because the two services only communicate through Kafka, either can be redeployed, scaled, or even temporarily down without the other crashing — messages simply queue up and drain once capacity is back.

## 4. Monitoring & Failure Response

| Signal | Tool | Alert condition |
|---|---|---|
| Request latency / error rate | Prometheus + Grafana | p99 latency or 5xx rate above SLO |
| Kafka consumer lag | Prometheus (Kafka exporter) | Lag growing over a sustained window → scale-out trigger + page on-call |
| DB connection pool | HikariCP metrics | Pool utilization near saturation |
| Dead-letter queue depth | Kafka exporter | Any message in `*.DLQ` → alert for manual/automated triage |
| Distributed traces | OpenTelemetry, correlated by `correlationId` | Used to debug a specific slow/failed request across both services |

**Recovery playbook basics:**
- Java pod crash → Kubernetes restarts it; in-flight request is lost to the client (safe to retry, thanks to idempotency keys), but no committed data is lost.
- Python pod crash mid-processing → Kafka redelivers the message to another consumer in the group; `processed_events` idempotency check prevents double-processing.
- Postgres primary failure → standby replica promoted (automated failover via a managed Postgres service or Patroni); Java reconnects via the connection string pointing at the new primary.
- Kafka broker failure → replication factor ≥ 3 means no data loss; partition leadership fails over automatically.

## 5. Rollback

Kubernetes rollback to the previous image tag: `kubectl rollout undo deployment/java-backend` (and equivalent for the Python service). DB migrations are written to be backward-compatible for at least one release so a rollback never requires a down-migration under time pressure.
