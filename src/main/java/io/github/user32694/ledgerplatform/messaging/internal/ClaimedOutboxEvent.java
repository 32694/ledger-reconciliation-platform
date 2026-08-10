package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.user32694.ledgerplatform.messaging.EventEnvelope;
import io.github.user32694.ledgerplatform.messaging.EventType;
import java.time.Instant;
import java.util.UUID;

record ClaimedOutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        EventType eventType,
        int schemaVersion,
        JsonNode payload,
        int attemptCount,
        Instant occurredAt) {

    EventEnvelope toEnvelope() {
        return new EventEnvelope(
                id,
                eventType,
                schemaVersion,
                aggregateType,
                aggregateId,
                occurredAt,
                payload);
    }
}
