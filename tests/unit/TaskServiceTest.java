package com.taskmgmt.service;

import com.taskmgmt.entity.Task;
import com.taskmgmt.entity.IdempotencyKey;
import com.taskmgmt.repository.IdempotencyKeyRepository;
import com.taskmgmt.repository.OutboxEventRepository;
import com.taskmgmt.repository.TaskAssigneeRepository;
import com.taskmgmt.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {

    @Test
    void updateTaskStatus_conflictingVersion_throws409EquivalentException() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAssigneeRepository assigneeRepo = mock(TaskAssigneeRepository.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        IdempotencyKeyRepository idempotencyRepo = mock(IdempotencyKeyRepository.class);
        TaskService service = new TaskService(taskRepo, assigneeRepo, outboxRepo, idempotencyRepo);

        UUID taskId = UUID.randomUUID();
        Task existing = new Task();
        existing.setVersion(5);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(existing));

        // client thinks version is 4 (stale) -> should be rejected
        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                service.updateTaskStatus(taskId, Task.TaskStatus.DONE, 4));

        verify(taskRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }

    /**
     * Regression test: a successful status update must publish a
     * "task.updated" outbox event. Before this fix, OutboxRelay had a
     * Kafka topic wired for "task.updated" that nothing ever produced —
     * status changes were silently invisible to Kafka consumers.
     */
    @Test
    void updateTaskStatus_success_publishesTaskUpdatedEvent() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAssigneeRepository assigneeRepo = mock(TaskAssigneeRepository.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        IdempotencyKeyRepository idempotencyRepo = mock(IdempotencyKeyRepository.class);
        TaskService service = new TaskService(taskRepo, assigneeRepo, outboxRepo, idempotencyRepo);

        UUID taskId = UUID.randomUUID();
        Task existing = new Task();
        existing.setId(taskId);
        existing.setVersion(2);
        existing.setStatus(Task.TaskStatus.OPEN);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(existing));
        when(taskRepo.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateTaskStatus(taskId, Task.TaskStatus.IN_PROGRESS, 2);

        verify(outboxRepo, times(1)).save(argThat(evt -> evt.getEventType().equals("task.updated")));
    }

    @Test
    void createTask_writesOutboxRowInSameTransaction() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAssigneeRepository assigneeRepo = mock(TaskAssigneeRepository.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        IdempotencyKeyRepository idempotencyRepo = mock(IdempotencyKeyRepository.class);
        TaskService service = new TaskService(taskRepo, assigneeRepo, outboxRepo, idempotencyRepo);

        Task task = new Task();
        task.setTitle("Write tests");
        when(taskRepo.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createTask(task, UUID.randomUUID());

        verify(outboxRepo, times(1)).save(argThat(evt -> evt.getEventType().equals("task.created")));
    }

    /**
     * Regression test for the fixed reassign bug: reassigning must write
     * to task_assignees, not overwrite Task.createdBy (createdBy is the
     * task's original-creator field and must never change on reassign).
     */
    @Test
    void reassignTasks_writesAssigneeRows_doesNotTouchCreatedBy() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAssigneeRepository assigneeRepo = mock(TaskAssigneeRepository.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        IdempotencyKeyRepository idempotencyRepo = mock(IdempotencyKeyRepository.class);
        TaskService service = new TaskService(taskRepo, assigneeRepo, outboxRepo, idempotencyRepo);

        UUID originalCreator = UUID.randomUUID();
        UUID newAssignee = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        Task task = new Task();
        task.setId(taskId);
        task.setCreatedBy(originalCreator);

        when(taskRepo.findAllByIdForUpdateOrdered(List.of(taskId))).thenReturn(List.of(task));

        service.reassignTasks(List.of(taskId), newAssignee);

        assertEquals(originalCreator, task.getCreatedBy(), "createdBy must be immutable on reassign");
        verify(assigneeRepo, times(1)).deleteByTaskIdIn(List.of(taskId));
        verify(assigneeRepo, times(1)).saveAll(argThat(assignees -> {
            @SuppressWarnings("unchecked")
            var list = (List<com.taskmgmt.entity.TaskAssignee>) assignees;
            return list.size() == 1
                    && list.get(0).getId().getTaskId().equals(taskId)
                    && list.get(0).getId().getUserId().equals(newAssignee);
        }));
    }

    /**
     * Regression test: retrying task creation with the same Idempotency-Key
     * must NOT create a second task. Before this fix, TaskController read
     * the header and threw it away — the idempotency_keys table existed
     * in the schema but nothing wrote to it.
     */
    @Test
    void createTaskIdempotent_retriedKey_returnsExistingTaskInsteadOfDuplicating() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAssigneeRepository assigneeRepo = mock(TaskAssigneeRepository.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        IdempotencyKeyRepository idempotencyRepo = mock(IdempotencyKeyRepository.class);
        TaskService service = new TaskService(taskRepo, assigneeRepo, outboxRepo, idempotencyRepo);

        String key = "client-retry-key-1";
        UUID userId = UUID.randomUUID();
        UUID existingTaskId = UUID.randomUUID();

        // Second call: key already claimed (tryClaim returns 0) and the
        // first call already recorded which task it produced.
        when(idempotencyRepo.tryClaim(key, userId)).thenReturn(0);
        IdempotencyKey stored = new IdempotencyKey();
        stored.setKey(key);
        stored.setUserId(userId);
        stored.setResponseBody(java.util.Map.of("taskId", existingTaskId.toString()));
        when(idempotencyRepo.findById(key)).thenReturn(Optional.of(stored));

        Task existingTask = new Task();
        existingTask.setId(existingTaskId);
        when(taskRepo.findById(existingTaskId)).thenReturn(Optional.of(existingTask));

        Task result = service.createTaskIdempotent(new Task(), UUID.randomUUID(), key, userId);

        assertEquals(existingTaskId, result.getId());
        verify(taskRepo, never()).save(any());       // no new task row written
        verify(outboxRepo, never()).save(any());      // no duplicate outbox event either
    }

    @Test
    void reassignTasks_missingTask_throwsInsteadOfSilentlySkipping() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAssigneeRepository assigneeRepo = mock(TaskAssigneeRepository.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        IdempotencyKeyRepository idempotencyRepo = mock(IdempotencyKeyRepository.class);
        TaskService service = new TaskService(taskRepo, assigneeRepo, outboxRepo, idempotencyRepo);

        UUID missingId = UUID.randomUUID();
        when(taskRepo.findAllByIdForUpdateOrdered(List.of(missingId))).thenReturn(List.of());

        assertThrows(TaskService.TaskNotFoundException.class, () ->
                service.reassignTasks(List.of(missingId), UUID.randomUUID()));

        verify(assigneeRepo, never()).deleteByTaskIdIn(any());
    }
}
