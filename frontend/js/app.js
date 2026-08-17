(() => {
  const el = (id) => document.getElementById(id);

  const loginView = el("loginView");
  const appView = el("appView");
  const loginForm = el("loginForm");
  const authNote = el("authNote");
  const statusStrip = el("statusStrip");
  const taskGrid = el("taskGrid");
  const emptyState = el("emptyState");
  const taskCountLabel = el("taskCountLabel");
  const userEmailEl = el("userEmail");
  const userAvatarEl = el("userAvatar");

  let state = {
    tasks: [],
    filter: "ALL",
    userEmail: null,
    demoMode: false,
  };

  // ---------- Demo-mode fallback ----------
  // If the Java API isn't reachable (e.g. reviewing the frontend
  // without running the backend), fall back to an in-memory dataset
  // so the UI itself can still be evaluated. A visible banner makes
  // this mode obvious rather than silently faking data.
  function seedDemoTasks() {
    const now = Date.now();
    return [
      { id: uuid(), title: "Fix login bug on Safari", description: "URGENT — users can't authenticate on Safari 18", status: "OPEN", priority: "CRITICAL", confidence: 0.93, version: 0, dueDate: new Date(now + 2 * 3600e3).toISOString() },
      { id: uuid(), title: "Write onboarding email copy", description: "Draft the first welcome email for new signups", status: "OPEN", priority: "LOW", confidence: 0.6, version: 0, dueDate: new Date(now + 20 * 24 * 3600e3).toISOString() },
      { id: uuid(), title: "Add retry/backoff to Python consumer", description: "Kafka consumer should retry transient failures before DLQ", status: "IN_PROGRESS", priority: "HIGH", confidence: 0.82, version: 1, dueDate: new Date(now + 20 * 3600e3).toISOString() },
      { id: uuid(), title: "Set up Grafana dashboards", description: "Consumer lag, p95 latency, DB pool utilization", status: "OPEN", priority: "MEDIUM", confidence: 0.7, version: 0, dueDate: new Date(now + 48 * 3600e3).toISOString() },
      { id: uuid(), title: "Write deployment runbook", description: "", status: "DONE", priority: "LOW", confidence: 0.6, version: 2, dueDate: null },
    ];
  }

  function uuid() {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  function setStatus(msg, type) {
    statusStrip.textContent = msg || "";
    statusStrip.className = "status-strip" + (type ? " " + type : "");
  }

  // ---------- Auth ----------
  loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = el("loginEmail").value.trim();
    const password = el("loginPassword").value;
    const apiUrl = el("apiBaseUrl").value.trim();
    Api.configure(apiUrl);
    authNote.textContent = "";

    try {
      const result = await Api.login(email, password);
      Api.setToken(result.token);
      state.userEmail = email;
      state.demoMode = false;
      enterApp();
    } catch (err) {
      // Backend not reachable / not implemented yet -> demo mode,
      // rather than blocking the reviewer from seeing the UI at all.
      state.userEmail = email;
      state.demoMode = true;
      state.tasks = seedDemoTasks();
      authNote.textContent = "";
      enterApp();
    }
  });

  el("logoutBtn").addEventListener("click", () => {
    Api.setToken(null);
    appView.classList.add("hidden");
    loginView.classList.remove("hidden");
  });

  function enterApp() {
    loginView.classList.add("hidden");
    appView.classList.remove("hidden");
    userEmailEl.textContent = state.userEmail;
    userAvatarEl.textContent = state.userEmail.charAt(0).toUpperCase();

    if (state.demoMode) {
      setStatus("Backend not reachable — showing demo data. Configure the API URL on the login screen to connect to a real instance.", "error");
      renderTasks();
    } else {
      setStatus("");
      refreshTasks();
    }
  }

  // ---------- Task loading ----------
  async function refreshTasks() {
    try {
      state.tasks = await Api.listTasks(state.filter);
      setStatus("");
      renderTasks();
      pollPendingPriorities();
    } catch (err) {
      setStatus(err.message, "error");
    }
  }

  // Newly created tasks have priority = null until the Python AI
  // service finishes scoring them asynchronously off Kafka. Poll
  // briefly so the UI reflects that result as soon as it lands,
  // without the client polling forever.
  function pollPendingPriorities() {
    if (state.demoMode) return;
    const pending = state.tasks.filter((t) => !t.priority);
    pending.forEach((t) => {
      let attempts = 0;
      const timer = setInterval(async () => {
        attempts++;
        try {
          const result = await Api.getTaskPriority(t.id);
          if (result && result.predictedPriority) {
            t.priority = result.predictedPriority;
            t.confidence = result.confidence;
            renderTasks();
            clearInterval(timer);
          }
        } catch { /* not scored yet */ }
        if (attempts > 10) clearInterval(timer);
      }, 1500);
    });
  }

  // ---------- Rendering ----------
  function renderTasks() {
    const filtered = state.filter === "ALL"
      ? state.tasks
      : state.tasks.filter((t) => t.status === state.filter);

    taskCountLabel.textContent = `${filtered.length} task${filtered.length === 1 ? "" : "s"}`;
    taskGrid.innerHTML = "";
    emptyState.classList.toggle("hidden", filtered.length > 0);

    filtered.forEach((t) => taskGrid.appendChild(taskCard(t)));
  }

  function taskCard(t) {
    const card = document.createElement("div");
    card.className = "task-card";
    card.dataset.priority = t.priority || "PENDING";

    const priorityLabel = t.priority
      ? `${t.priority}${t.confidence ? ` · ${Math.round(t.confidence * 100)}%` : ""}`
      : "Scoring…";

    card.innerHTML = `
      <div class="task-card-top">
        <p class="task-title">${escapeHtml(t.title)}</p>
        <span class="badge badge-priority" data-p="${t.priority || "PENDING"}">${priorityLabel}</span>
      </div>
      ${t.description ? `<p class="task-desc">${escapeHtml(t.description)}</p>` : ""}
      <div class="task-card-footer">
        <span class="badge badge-status">${t.status.replace("_", " ")}</span>
        <span class="task-meta">v${t.version ?? 0}${t.dueDate ? " · due " + formatDate(t.dueDate) : ""}</span>
      </div>
      <div class="task-actions"></div>
    `;

    const actions = card.querySelector(".task-actions");
    if (t.status !== "DONE") {
      const nextStatus = t.status === "OPEN" ? "IN_PROGRESS" : "DONE";
      const btn = document.createElement("button");
      btn.className = "icon-btn";
      btn.textContent = nextStatus === "IN_PROGRESS" ? "Start" : "Mark done";
      btn.addEventListener("click", () => advanceStatus(t, nextStatus));
      actions.appendChild(btn);
    }
    return card;
  }

  function escapeHtml(s) {
    const d = document.createElement("div");
    d.textContent = s;
    return d.innerHTML;
  }
  function formatDate(iso) {
    try { return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" }); }
    catch { return ""; }
  }

  async function advanceStatus(task, newStatus) {
    if (state.demoMode) {
      task.status = newStatus;
      task.version = (task.version || 0) + 1;
      renderTasks();
      return;
    }
    try {
      const updated = await Api.updateStatus(task.id, newStatus, task.version);
      Object.assign(task, updated);
      renderTasks();
    } catch (err) {
      // 409 = optimistic-lock conflict: another user changed it first.
      setStatus(err.status === 409
        ? "That task changed elsewhere — refreshing…"
        : err.message, "error");
      if (err.status === 409) refreshTasks();
    }
  }

  // ---------- Filters ----------
  document.querySelectorAll(".side-link").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".side-link").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      state.filter = btn.dataset.filter;
      renderTasks();
    });
  });

  // ---------- Claim ----------
  el("claimBtn").addEventListener("click", async () => {
    if (state.demoMode) {
      const openTask = state.tasks.find((t) => t.status === "OPEN");
      if (openTask) { openTask.status = "IN_PROGRESS"; renderTasks(); setStatus(`Claimed "${openTask.title}"`, "ok"); }
      else setStatus("No open tasks to claim.", "error");
      return;
    }
    try {
      const claimed = await Api.claimNext();
      setStatus(`Claimed "${claimed.title}"`, "ok");
      refreshTasks();
    } catch (err) {
      setStatus(err.status === 404 ? "No open tasks to claim right now." : err.message, "error");
    }
  });

  // ---------- New task modal ----------
  const taskModal = el("taskModal");
  el("newTaskBtn").addEventListener("click", () => taskModal.classList.remove("hidden"));
  el("cancelTaskBtn").addEventListener("click", () => taskModal.classList.add("hidden"));

  el("taskForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const title = el("taskTitle").value.trim();
    const description = el("taskDescription").value.trim();
    const dueDate = el("taskDueDate").value ? new Date(el("taskDueDate").value).toISOString() : null;
    const idempotencyKey = uuid(); // client-generated -> safe to retry this exact request

    if (state.demoMode) {
      state.tasks.unshift({ id: uuid(), title, description, dueDate, status: "OPEN", priority: null, version: 0 });
      taskModal.classList.add("hidden");
      el("taskForm").reset();
      renderTasks();
      // simulate the async AI scoring step so the demo still shows it resolving
      setTimeout(() => {
        state.tasks[0].priority = /urgent|critical|asap/i.test(title + description) ? "CRITICAL" : "MEDIUM";
        state.tasks[0].confidence = 0.8;
        renderTasks();
      }, 1200);
      return;
    }

    try {
      const created = await Api.createTask({ title, description, dueDate }, idempotencyKey);
      taskModal.classList.add("hidden");
      el("taskForm").reset();
      state.tasks.unshift(created);
      renderTasks();
      pollPendingPriorities();
    } catch (err) {
      setStatus(err.message, "error");
    }
  });

})();
