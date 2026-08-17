package com.taskmgmt.repository;

import com.taskmgmt.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Batch of unpublished rows, oldest first, polled by OutboxRelay
     * every 500ms. Capped at 100 per tick so one huge backlog doesn't
     * hold the transaction open too long.
     */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
