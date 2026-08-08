package io.github.user32694.ledgerplatform.audit;

public record AuditCommand(
        String actor,
        AuditAction action,
        String aggregateType,
        String aggregateId,
        AuditOutcome outcome,
        String summary,
        String correlationReference) {}
