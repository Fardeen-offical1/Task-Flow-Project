package com.taskmgmt.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to idempotency_keys (see database/01_schema.sql). The table already
 * existed, but nothing in the app wrote to it: TaskController accepted an
 * Idempotency-Key header and just... dropped it. A client retry (timeout,
 * flaky network) with the same key created a second task instead of
 * returning the first one. See TaskService.createTaskIdempotent /
 * IdempotencyKeyRepository.tryClaim for the fix.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key")
    private String key;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Convert(converter = OutboxEvent.JsonbPayloadConverter.class)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private Map<String, Object> responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
