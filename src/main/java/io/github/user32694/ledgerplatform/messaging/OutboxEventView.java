package io.github.user32694.ledgerplatform.messaging;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventView(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant publishedAt,
        String lastError,
        Instant occurredAt,
        Instant createdAt) {}
