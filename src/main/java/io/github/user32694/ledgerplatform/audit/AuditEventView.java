package io.github.user32694.ledgerplatform.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventView(
        UUID id,
        String actor,
        AuditAction action,
        String aggregateType,
        String aggregateId,
        AuditOutcome outcome,
        String summary,
        String correlationReference,
        Instant occurredAt) {}
