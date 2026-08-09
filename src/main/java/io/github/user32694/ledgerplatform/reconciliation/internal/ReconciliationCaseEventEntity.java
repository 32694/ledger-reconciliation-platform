package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseEventView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionCode;
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
@Table(name = "reconciliation_case_event", schema = "reconciliation")
class ReconciliationCaseEventEntity {
    @Id
    private UUID id;

    @Column(name = "result_id", nullable = false)
    private UUID resultId;

    @Column(nullable = false, length = 16)
    private String action;

    @Column(nullable = false, length = 128)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code", length = 32)
    private ResolutionCode resolutionCode;

    @Column(length = 2000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationCaseEventEntity() {}

    static ReconciliationCaseEventEntity claimed(UUID resultId, String actor, Instant createdAt) {
        return create(resultId, "CLAIMED", actor, null, null, createdAt);
    }

    static ReconciliationCaseEventEntity released(UUID resultId, String actor, Instant createdAt) {
        return create(resultId, "RELEASED", actor, null, null, createdAt);
    }

    static ReconciliationCaseEventEntity resolved(
            UUID resultId,
            String actor,
            ResolutionCode resolutionCode,
            String note,
            Instant createdAt) {
        return create(resultId, "RESOLVED", actor, resolutionCode, note, createdAt);
    }

    private static ReconciliationCaseEventEntity create(
            UUID resultId,
            String action,
            String actor,
            ResolutionCode resolutionCode,
            String note,
            Instant createdAt) {
        var event = new ReconciliationCaseEventEntity();
        event.id = UUID.randomUUID();
        event.resultId = resultId;
        event.action = action;
        event.actor = actor;
        event.resolutionCode = resolutionCode;
        event.note = note;
        event.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        return event;
    }

    ReconciliationCaseEventView toView() {
        return new ReconciliationCaseEventView(
                id, resultId, action, actor, resolutionCode, note, createdAt);
    }
}
