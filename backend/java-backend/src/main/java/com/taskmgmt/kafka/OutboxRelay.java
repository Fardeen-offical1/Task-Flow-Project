package com.taskmgmt.kafka;

import com.taskmgmt.entity.OutboxEvent;
import com.taskmgmt.repository.OutboxEventRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Polls unpublished outbox rows and pushes them to Kafka, then marks
 * them published. This decouples "commit the DB change" from "publish
 * the event" so a crash between those two steps can never lose data —
 * on restart, the relay just finds the row still unpublished and
 * retries. Kafka producer uses acks=all + idempotent producer config
 * so a retried publish never duplicates on the broker side either.
 */
@Component
public class OutboxRelay {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 500) // poll twice a second
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : pending) {
            try {
                String key = event.getAggregateId().toString(); // partition by taskId -> ordering per task
                String value = event.toJson(); // includes eventId, correlationId, payload

                kafkaTemplate.send(topicFor(event.getEventType()), key, value).get(); // sync send for the relay
                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);
            } catch (Exception e) {
                // leave unpublished; next tick retries. Alert if an event
                // stays unpublished beyond a threshold (monitored via
                // a Prometheus gauge on outbox age).
                break;
            }
        }
    }

    private String topicFor(String eventType) {
        return switch (eventType) {
            case "task.created" -> "task.created";
            case "task.updated" -> "task.updated";
            default -> throw new IllegalStateException("Unknown event type: " + eventType);
        };
    }
}
