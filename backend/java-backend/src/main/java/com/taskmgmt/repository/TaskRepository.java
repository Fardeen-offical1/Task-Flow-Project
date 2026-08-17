package com.taskmgmt.repository;

import com.taskmgmt.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    /**
     * Backs GET /tasks (with and without a status filter). Capped at 200
     * and newest-first — this is a dashboard listing, not a paginated
     * export endpoint; a client that needs more should page by createdAt.
     */
    List<Task> findTop200ByOrderByCreatedAtDesc();
    List<Task> findTop200ByStatusOrderByCreatedAtDesc(Task.TaskStatus status);

    /**
     * Atomically claims one open task for a caller. FOR UPDATE SKIP LOCKED
     * means concurrent callers never block on each other's row lock —
     * whoever wins just moves on to the next available row instead of
     * queuing behind a locked one. Used by TaskService.claimNextAvailableTask
     * (Requirement 5/7 — high-contention correctness without serializing
     * every claim through one lock).
     */
    @Query(value = """
            SELECT * FROM tasks
            WHERE status = 'OPEN'
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<Task> findNextClaimableForUpdateSkipLocked();

    /**
     * Locks all given rows in a single statement, in a globally
     * consistent id order (the caller sorts `ids` first). Locking
     * in a fixed global order across every transaction is what
     * prevents the classic deadlock where two transactions each
     * hold one row and wait on the other's row (Requirement 7).
     */
    @Query(value = """
            SELECT * FROM tasks
            WHERE id = ANY(:ids)
            ORDER BY id
            FOR UPDATE
            """, nativeQuery = true)
    List<Task> findAllByIdForUpdateOrdered(@Param("ids") List<UUID> ids);
}
