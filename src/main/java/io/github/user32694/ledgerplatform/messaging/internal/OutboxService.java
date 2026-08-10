package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.OutboxApi;
import io.github.user32694.ledgerplatform.messaging.OutboxCommand;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxService implements OutboxApi {
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository repository;

    OutboxService(ObjectMapper objectMapper, OutboxEventRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @Override
    @Transactional
    public UUID append(OutboxCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Outbox command is required");
        }
        if (command.eventType() == null) {
            throw new IllegalArgumentException("Event type is required");
        }
        String aggregateType = requireText(command.aggregateType(), "Aggregate type", 64);
        String aggregateId = requireText(command.aggregateId(), "Aggregate id", 128);
        if (command.schemaVersion() <= 0) {
            throw new IllegalArgumentException("Schema version must be greater than zero");
        }
        if (command.payload() == null) {
            throw new IllegalArgumentException("Payload is required");
        }
        if (command.occurredAt() == null) {
            throw new IllegalArgumentException("Occurred at is required");
        }

        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = new OutboxEventEntity(
                eventId,
                aggregateType,
                aggregateId,
                command.eventType(),
                command.schemaVersion(),
                objectMapper.valueToTree(command.payload()),
                OutboxStatus.PENDING,
                0,
                createdAt,
                null,
                null,
                null,
                command.occurredAt(),
                createdAt);
        repository.save(event);
        return eventId;
    }

    private static String requireText(String value, String field, int maximumCodePoints) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maximumCodePoints + " characters");
        }
        return normalized;
    }
}
