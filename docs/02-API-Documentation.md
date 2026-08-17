# API Documentation
**Real-Time Distributed Task-Management System — Fardeen, Pakistan**

Base URL: `https://api.taskmgmt.example.com/api/v1`
Auth: `Authorization: Bearer <JWT>` on every endpoint except `/auth/register` and `/auth/login`.

> The response shapes below are exactly what the current code returns —
> flat JSON, no `{ "data": ... }` envelope and no RFC 7807 problem-json.
> An earlier draft of this doc described those as if already implemented;
> they're a reasonable future improvement, not something to claim as done.

---

## Auth

### `POST /auth/register`
Public self-registration. Always creates a `MEMBER` — promoting someone
to `MANAGER`/`ADMIN` is a separate, privileged operation (today: a direct
DB update by an operator; not yet exposed via API).
```json
// request
{ "email": "user@example.com", "password": "at-least-8-chars" }
// response 201
{ "id": "uuid", "email": "user@example.com", "role": "MEMBER" }
// response 409 if the email is already registered
```

### `POST /auth/login`
```json
// request
{ "email": "user@example.com", "password": "•••••••" }
// response 200
{ "token": "jwt", "email": "user@example.com", "role": "MEMBER" }
```
The token is a single short-lived (default 60 min, `app.jwt.expiry-minutes`)
JWT — there is no refresh-token flow yet; the client re-authenticates with
`/auth/login` once it expires.

---

## Tasks

### `GET /tasks?status=OPEN`
List tasks, newest first (capped at 200). `status` is optional (`OPEN`, `IN_PROGRESS`, `DONE`, `CANCELLED`); omit it to list all. Backs the frontend's task grid.

Roles: any authenticated user

### `POST /tasks`
Create a task. **Requires** header `Idempotency-Key: <client-generated-uuid>` — replaying the same key returns the original task instead of creating a duplicate. If a second request with the same key arrives while the first is still being processed, the server returns `409 Conflict` ("already being processed") rather than blocking or racing — the client should retry shortly.

Roles: `MEMBER`, `MANAGER`, `ADMIN`

```json
// request
{ "title": "Fix login bug", "description": "Users can't log in on Safari", "dueDate": "2026-08-20T10:00:00Z" }
// response 201
{ "data": { "id": "uuid", "title": "Fix login bug", "status": "OPEN", "version": 0, "priority": null } }
```
`priority` is `null` initially — it is filled in asynchronously once the Python AI service scores the task (usually within a few hundred ms).

### `GET /tasks/{id}`
Roles: any authenticated user. Response is cached (Redis, ~30–60s TTL, invalidated on write).

### `GET /tasks?status=&assignee=&page=`
List/filter tasks with pagination.

### `PATCH /tasks/{id}/status?status=IN_PROGRESS`
**Requires** header `If-Match: <version>` (optimistic-lock check).
- `200` — updated.
- `409 Conflict` — the task was modified concurrently; re-fetch and retry.

### `POST /tasks/claim`
Atomically claims the next open task for the calling user (`SELECT ... FOR UPDATE SKIP LOCKED`), safe under heavy concurrent claiming.

### `POST /tasks/reassign?newAssignee=<uuid>`
Roles: `MANAGER`, `ADMIN`. Body: `["taskId1", "taskId2", ...]`. Bulk-safe against deadlocks (locks acquired in sorted-id order internally).

### `GET /tasks/{id}/priority`
Returns the latest AI-predicted priority:
```json
{ "data": { "priority": "HIGH", "confidence": 0.82, "modelVersion": "v1.2.0", "updatedAt": "2026-08-15T09:00:00Z" } }
```

---

## Rate Limiting
100 requests/minute per user (Redis token bucket). Exceeding it returns `429 Too Many Requests` with a `Retry-After` header.

## Error Codes

| Status | Meaning |
|---|---|
| 400 | Validation error |
| 401 | Missing/invalid token |
| 403 | Role/permission denied |
| 404 | Task or resource not found |
| 409 | Optimistic-lock conflict — refetch and retry |
| 429 | Rate limit exceeded |
| 500 | Unexpected server error (logged with correlationId for tracing) |
