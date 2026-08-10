package io.github.user32694.ledgerplatform.messaging;

import java.time.Instant;
import java.util.Map;

public record OutboxCommand(
        EventType eventType,
        String aggregateType,
        String aggregateId,
        int schemaVersion,
        Map<String, Object> payload,
        Instant occurredAt) {
    public OutboxCommand {
        if (payload != null) {
            payload = Map.copyOf(payload);
        }
    }
}
