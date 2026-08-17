package com.taskmgmt.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to outbox_events (see database/01_schema.sql).
 *
 * Written in the SAME transaction as the business change (see
 * TaskService.createTask). OutboxRelay polls rows where
 * publishedAt IS NULL and pushes them to Kafka, then stamps
 * publishedAt. This is what guarantees "task.created" is never
 * lost even if the process crashes right after commit.
 *
 * eventId is separate from the bigserial `id` (which is just the
 * outbox row's own PK) because the Python consumer's idempotency
 * table (processed_events) keys off eventId, not the outbox row id.
 * Run this migration once against 01_schema.sql if it isn't there yet:
 *   ALTER TABLE outbox_events ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid();
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
public class OutboxEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId = UUID.randomUUID();

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Convert(converter = JsonbPayloadConverter.class)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Serialized form sent to Kafka by OutboxRelay. Field names match
     * what the Python service's main.py expects: eventId, correlationId,
     * taskId (from payload), title, description, dueDate.
     */
    public String toJson() {
        try {
            Map<String, Object> body = new java.util.HashMap<>(payload);
            body.put("eventId", eventId.toString());
            body.put("correlationId", correlationId.toString());
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event " + id, e);
        }
    }

    /** JSONB <-> Map converter so the payload column round-trips through JPA. */
    @Converter
    public static class JsonbPayloadConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            try {
                return attribute == null ? null : MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize payload", e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            try {
                return dbData == null ? null : MAPPER.readValue(dbData, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize payload", e);
            }
        }
    }
}
