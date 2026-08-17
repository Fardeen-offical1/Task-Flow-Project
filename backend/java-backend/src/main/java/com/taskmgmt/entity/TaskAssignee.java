package com.taskmgmt.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps to task_assignees (see database/01_schema.sql). A task can have
 * multiple assignees; this is the join row TaskService.reassignTasks
 * writes to instead of clobbering Task.createdBy (that field records
 * who *created* the task and must never be overwritten by a reassign).
 */
@Entity
@Table(name = "task_assignees")
@Getter
@Setter
@NoArgsConstructor
public class TaskAssignee {

    @EmbeddedId
    private Id id;

    private Instant assignedAt = Instant.now();

    public TaskAssignee(UUID taskId, UUID userId) {
        this.id = new Id(taskId, userId);
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "task_id")
        private UUID taskId;

        @Column(name = "user_id")
        private UUID userId;

        public Id(UUID taskId, UUID userId) {
            this.taskId = taskId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(taskId, id.taskId) && Objects.equals(userId, id.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, userId);
        }
    }
}
