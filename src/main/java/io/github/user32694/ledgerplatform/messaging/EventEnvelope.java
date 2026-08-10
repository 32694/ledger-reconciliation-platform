package io.github.user32694.ledgerplatform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        EventType eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        JsonNode payload) {}
