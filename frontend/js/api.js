/**
 * Thin client for the Java backend REST API.
 * Talks only to the Java service — the frontend never calls the
 * Python AI service or Kafka directly, matching the architecture
 * where Python is purely an internal, queue-driven worker.
 */
const Api = (() => {
  let baseUrl = "http://localhost:8080/api/v1";
  let accessToken = null;

  function configure(url) { baseUrl = url.replace(/\/$/, ""); }
  function setToken(token) { accessToken = token; }

  function headers(extra = {}) {
    const h = { "Content-Type": "application/json", ...extra };
    if (accessToken) h["Authorization"] = `Bearer ${accessToken}`;
    return h;
  }

  async function handle(res) {
    if (res.status === 204) return null;
    let body;
    try { body = await res.json(); } catch { body = null; }
    if (!res.ok) {
      const message = body?.error?.detail || body?.detail || `Request failed (${res.status})`;
      const err = new Error(message);
      err.status = res.status;
      throw err;
    }
    return body?.data ?? body;
  }

  return {
    configure,
    setToken,

    login(email, password) {
      return fetch(`${baseUrl}/auth/login`, {
        method: "POST",
        headers: headers(),
        body: JSON.stringify({ email, password }),
      }).then(handle);
    },

    listTasks(status) {
      const qs = status && status !== "ALL" ? `?status=${encodeURIComponent(status)}` : "";
      return fetch(`${baseUrl}/tasks${qs}`, { headers: headers() }).then(handle);
    },

    createTask(task, idempotencyKey) {
      return fetch(`${baseUrl}/tasks`, {
        method: "POST",
        headers: headers({ "Idempotency-Key": idempotencyKey }),
        body: JSON.stringify(task),
      }).then(handle);
    },

    getTaskPriority(taskId) {
      return fetch(`${baseUrl}/tasks/${taskId}/priority`, { headers: headers() }).then(handle);
    },

    updateStatus(taskId, status, version) {
      return fetch(`${baseUrl}/tasks/${taskId}/status?status=${status}`, {
        method: "PATCH",
        headers: headers({ "If-Match": String(version) }),
      }).then(handle);
    },

    claimNext() {
      return fetch(`${baseUrl}/tasks/claim`, { method: "POST", headers: headers() }).then(handle);
    },
  };
})();
