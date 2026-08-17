package com.taskmgmt.repository;

import com.taskmgmt.entity.TaskAssignee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TaskAssigneeRepository extends JpaRepository<TaskAssignee, TaskAssignee.Id> {

    /**
     * Clears existing assignees for a batch of tasks before writing the
     * new assignment, so a reassign is a clean replace rather than an
     * ever-growing list of stale assignees.
     */
    @Modifying
    @Query("DELETE FROM TaskAssignee ta WHERE ta.id.taskId IN :taskIds")
    void deleteByTaskIdIn(@Param("taskIds") List<UUID> taskIds);
}
