package com.taskmgmt.service;

import com.taskmgmt.entity.Task;
import com.taskmgmt.entity.TaskAssignee;
import com.taskmgmt.entity.OutboxEvent;
import com.taskmgmt.entity.IdempotencyKey;
import com.taskmgmt.repository.TaskRepository;
import com.taskmgmt.repository.TaskAssigneeRepository;
import com.taskmgmt.repository.OutboxEventRepository;
import com.taskmgmt.repository.IdempotencyKeyRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskAssigneeRepository taskAssigneeRepository;
    private final OutboxEventRepository outboxRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public TaskService(TaskRepository taskRepository,
                        TaskAssigneeRepository taskAssigneeRepository,
                        OutboxEventRepository outboxRepository,
                        IdempotencyKeyRepository idempotencyKeyRepository) {
        this.taskRepository = taskRepository;
        this.taskAssigneeRepository = taskAssigneeRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Entry point the controller actually calls now. Wraps createTask()
     * with the idempotency_keys table (which already existed in the
     * schema but was never used): the INSERT ... ON CONFLICT DO NOTHING
     * in tryClaim() is atomic, so of two concurrent requests carrying the
     * same Idempotency-Key header, exactly one proceeds to create the
     * task; the other is told a request with this key is already being
     * handled instead of silently creating a duplicate.
     */
    @Transactional
    public Task createTaskIdempotent(Task task, UUID correlationId, String idempotencyKey, UUID userId) {
        int claimed = idempotencyKeyRepository.tryClaim(idempotencyKey, userId);

        if (claimed == 0) {
            IdempotencyKey existing = idempotencyKeyRepository.findById(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after conflict: " + idempotencyKey));
            if (existing.getResponseBody() == null) {
                // Row was claimed by another in-flight request that hasn't
                // finished yet. Tell the client to retry shortly rather
                // than block here or (worse) create a second task.
                throw new DuplicateInFlightException(idempotencyKey);
            }
            UUID existingTaskId = UUID.fromString((String) existing.getResponseBody().get("taskId"));
            return getTask(existingTaskId);
        }

        Task saved = createTask(task, correlationId);

        IdempotencyKey key = idempotencyKeyRepository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key row missing after claim: " + idempotencyKey));
        key.setResponseBody(Map.of("taskId", saved.getId().toString()));
        idempotencyKeyRepository.save(key);

        return saved;
    }

    /**
     * Create a task and atomically write an outbox row in the SAME db
     * transaction. A separate relay process (OutboxRelay) polls
     * unpublished rows and pushes them to Kafka. This guarantees the
     * "task.created" event is never lost even if the process crashes
     * right after this method returns (Requirement 6).
     */
    @Transactional
    @CacheEvict(value = "taskLists", allEntries = true)
    public Task createTask(Task task, UUID correlationId) {
        Task saved = taskRepository.save(task);

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("TASK");
        event.setAggregateId(saved.getId());
        event.setEventType("task.created");
        event.setCorrelationId(correlationId);
        event.setPayload(Map.of(
                "taskId", saved.getId().toString(),
                "title", saved.getTitle(),
                "description", saved.getDescription(),
                "dueDate", String.valueOf(saved.getDueDate())
        ));
        outboxRepository.save(event);

        return saved;
    }

    /**
     * Backs GET /tasks (frontend's task grid). The "taskLists" cache name
     * already existed on createTask()'s @CacheEvict — that eviction was
     * written for this method, but this method itself was never added,
     * so the frontend's list call had nothing to hit (404) even though
     * the cache invalidation was ready and waiting for it.
     */
    @Cacheable(value = "taskLists", key = "#status != null ? #status : 'ALL'")
    public List<Task> listTasks(Task.TaskStatus status) {
        return status == null
                ? taskRepository.findTop200ByOrderByCreatedAtDesc()
                : taskRepository.findTop200ByStatusOrderByCreatedAtDesc(status);
    }

    @Cacheable(value = "task", key = "#id")
    public Task getTask(UUID id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    /**
     * Optimistic-lock-aware update. The client must supply the version
     * it last read (e.g. via an If-Match header). If another writer
     * already updated the row, Hibernate throws
     * ObjectOptimisticLockingFailureException and we surface a 409 so
     * the client can re-fetch and retry — no silent lost updates.
     *
     * Also writes a "task.updated" outbox event in the same transaction
     * as the status change (Requirement 3 — real-time updates over the
     * message queue). Previously OutboxRelay.topicFor() already had a
     * "task.updated" case wired to a Kafka topic, but nothing in this
     * class ever created an event of that type, so the topic was dead —
     * downstream consumers never learned when a task's status changed.
     */
    @Transactional
    @CacheEvict(value = "task", key = "#taskId")
    public Task updateTaskStatus(UUID taskId, Task.TaskStatus newStatus, int expectedVersion) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (!task.getVersion().equals(expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(Task.class, taskId);
        }
        task.setStatus(newStatus);
        Task saved = taskRepository.save(task); // version auto-increments here
        publishTaskUpdated(saved, UUID.randomUUID());
        return saved;
    }

    /**
     * Atomic "claim" for high-contention scenarios (many users racing to
     * grab the same open task). Uses SELECT ... FOR UPDATE SKIP LOCKED
     * at the repository/native-query level so concurrent claimers never
     * block each other — whoever gets the row first wins, everyone else
     * simply sees it's gone. See TaskRepository.findNextClaimable().
     */
    @Transactional
    @CacheEvict(value = "task", key = "#result.id")
    public Task claimNextAvailableTask(UUID userId) {
        Task task = taskRepository.findNextClaimableForUpdateSkipLocked()
                .orElseThrow(() -> new NoTaskAvailableException());
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        Task saved = taskRepository.save(task);
        publishTaskUpdated(saved, UUID.randomUUID());
        return saved;
    }

    /** Shared outbox write for any "task.updated" change (status change, claim). */
    private void publishTaskUpdated(Task task, UUID correlationId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("TASK");
        event.setAggregateId(task.getId());
        event.setEventType("task.updated");
        event.setCorrelationId(correlationId);
        event.setPayload(Map.of(
                "taskId", task.getId().toString(),
                "status", task.getStatus().name(),
                "version", task.getVersion()
        ));
        outboxRepository.save(event);
    }

    /**
     * Bulk reassignment across multiple tasks — the classic deadlock
     * scenario when two transactions lock the same rows in different
     * orders. We always sort ids first so every transaction acquires
     * locks in the same global order (Requirement 7 — deadlocks).
     *
     * Reassigning must NOT touch Task.createdBy — that field records
     * who originally created the task and is immutable history. The
     * actual assignment lives in task_assignees, so we lock the task
     * rows (to serialize concurrent reassigns of the same tasks),
     * then replace each task's assignee row: delete the old one(s),
     * insert the new one. Both writes happen in the same transaction
     * as the row lock, so a reader never observes a task with zero
     * or two "current" assignees.
     */
    @Transactional
    @CacheEvict(value = "task", allEntries = true)
    public void reassignTasks(List<UUID> taskIds, UUID newAssignee) {
        List<UUID> sorted = taskIds.stream().distinct().sorted().toList();

        // Locks the task rows themselves (in a fixed order) so two
        // concurrent reassigns of overlapping task sets serialize
        // instead of deadlocking or racing on the assignee rows below.
        List<Task> tasks = taskRepository.findAllByIdForUpdateOrdered(sorted);
        if (tasks.size() != sorted.size()) {
            throw new TaskNotFoundException(sorted.stream()
                    .filter(id -> tasks.stream().noneMatch(t -> t.getId().equals(id)))
                    .findFirst().orElse(null));
        }

        taskAssigneeRepository.deleteByTaskIdIn(sorted);
        List<TaskAssignee> newAssignments = sorted.stream()
                .map(taskId -> new TaskAssignee(taskId, newAssignee))
                .toList();
        taskAssigneeRepository.saveAll(newAssignments);
    }

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(UUID id) { super("Task not found: " + id); }
    }
    public static class NoTaskAvailableException extends RuntimeException {
        public NoTaskAvailableException() { super("No claimable task available"); }
    }
    public static class DuplicateInFlightException extends RuntimeException {
        public DuplicateInFlightException(String key) {
            super("A request with idempotency key " + key + " is already being processed");
        }
    }
}
