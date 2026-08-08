package io.github.user32694.ledgerplatform.audit.internal;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditEventView;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "audit_event", schema = "audit")
class AuditEventEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditOutcome outcome;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "correlation_reference", length = 128)
    private String correlationReference;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEventEntity() {}

    AuditEventEntity(
            UUID id,
            String actor,
            AuditAction action,
            String aggregateType,
            String aggregateId,
            AuditOutcome outcome,
            String summary,
            String correlationReference,
            Instant occurredAt) {
        this.id = id;
        this.actor = actor;
        this.action = action;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.outcome = outcome;
        this.summary = summary;
        this.correlationReference = correlationReference;
        this.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    AuditEventView toView() {
        return new AuditEventView(
                id,
                actor,
                action,
                aggregateType,
                aggregateId,
                outcome,
                summary,
                correlationReference,
                occurredAt);
    }
}
