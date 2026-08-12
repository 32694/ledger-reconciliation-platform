package io.github.user32694.ledgerplatform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** RabbitMQ 中传输的稳定事件契约；payload 由具体事件类型解释。 */
public record EventEnvelope(
        UUID eventId,
        EventType eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        JsonNode payload) {}
