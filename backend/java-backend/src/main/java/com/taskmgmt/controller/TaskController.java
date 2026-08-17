package com.taskmgmt.controller;

import com.taskmgmt.entity.Task;
import com.taskmgmt.repository.TaskPriorityPredictionRepository;
import com.taskmgmt.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskPriorityPredictionRepository predictionRepository;

    public TaskController(TaskService taskService, TaskPriorityPredictionRepository predictionRepository) {
        this.taskService = taskService;
        this.predictionRepository = predictionRepository;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.List<Task>> listTasks(@RequestParam(required = false) Task.TaskStatus status) {
        return ResponseEntity.ok(taskService.listTasks(status));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MEMBER','MANAGER','ADMIN')")
    public ResponseEntity<Task> createTask(
            @Valid @RequestBody Task task,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        task.setCreatedBy(userId);

        UUID correlationId = UUID.randomUUID();
        Task created = taskService.createTaskIdempotent(task, correlationId, idempotencyKey, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Task> getTask(@PathVariable UUID id) {
        return ResponseEntity.ok(taskService.getTask(id));
    }

    @GetMapping("/{id}/priority")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getPriority(@PathVariable UUID id) {
        return predictionRepository.findTopByTaskIdOrderByCreatedAtDesc(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body("Priority not yet computed for this task"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Task> updateStatus(
            @PathVariable UUID id,
            @RequestParam Task.TaskStatus status,
            @RequestHeader("If-Match") int expectedVersion) {
        return ResponseEntity.ok(taskService.updateTaskStatus(id, status, expectedVersion));
    }

    @PostMapping("/claim")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Task> claim(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(taskService.claimNextAvailableTask(userId));
    }

    @PostMapping("/reassign")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<Void> reassign(@RequestBody java.util.List<UUID> taskIds,
                                          @RequestParam UUID newAssignee) {
        taskService.reassignTasks(taskIds, newAssignee);
        return ResponseEntity.noContent().build();
    }

    // Optimistic-lock conflicts surface as 409 so clients know to refetch & retry.
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<String> handleConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("Task was modified concurrently. Please refetch and retry.");
    }

    // Another request with the same Idempotency-Key is still being processed.
    @ExceptionHandler(TaskService.DuplicateInFlightException.class)
    public ResponseEntity<String> handleDuplicateInFlight() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("A request with this Idempotency-Key is already being processed. Retry shortly.");
    }
}