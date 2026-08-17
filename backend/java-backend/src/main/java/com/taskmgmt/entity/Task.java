package com.taskmgmt.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tasks")
@Getter
@Setter
public class Task {

    @Id
    @GeneratedValue
    private UUID id;

    private String title;
    private String description;

    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.OPEN;

    private String priority;          // filled in asynchronously by the Python AI service

    @Column(name = "priority_confidence", precision = 4, scale = 3)
    private BigDecimal priorityConfidence;

    @Column(name = "created_by")
    private UUID createdBy;

    private Instant dueDate;

    // Optimistic locking: JPA auto-increments this on every UPDATE and
    // throws OptimisticLockException if a concurrent writer already bumped it.
    // This is what prevents two managers from silently clobbering each
    // other's edits to the same task (Requirement 7 — race conditions).
    @Version
    private Integer version;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    // --- getters / setters omitted for brevity ---

    public enum TaskStatus { OPEN, IN_PROGRESS, DONE, CANCELLED }
}
