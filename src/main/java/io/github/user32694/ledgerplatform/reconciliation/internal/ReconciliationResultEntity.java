package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
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
@Table(name = "reconciliation_result", schema = "reconciliation")
class ReconciliationResultEntity {
    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "statement_entry_id")
    private UUID statementEntryId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false, length = 32)
    private ResultType resultType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 16)
    private ResolutionStatus resolutionStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationResultEntity() {}

    static ReconciliationResultEntity from(
            UUID batchId,
            ReconciliationMatcher.ResultDraft draft,
            Instant createdAt) {
        var entity = new ReconciliationResultEntity();
        entity.id = UUID.randomUUID();
        entity.batchId = batchId;
        entity.statementEntryId = draft.statementEntryId();
        entity.paymentId = draft.paymentId();
        entity.resultType = draft.resultType();
        entity.resolutionStatus = draft.resolutionStatus();
        entity.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        return entity;
    }

    UUID id() {
        return id;
    }

    UUID batchId() {
        return batchId;
    }

    UUID statementEntryId() {
        return statementEntryId;
    }

    UUID paymentId() {
        return paymentId;
    }

    ResultType resultType() {
        return resultType;
    }

    ResolutionStatus resolutionStatus() {
        return resolutionStatus;
    }

    ReconciliationResolutionEntity resolve(String note, String operator, Instant resolvedAt) {
        if (resultType == ResultType.MATCHED) {
            throw new IllegalStateException("MATCHED result does not require resolution");
        }
        if (resolutionStatus != ResolutionStatus.OPEN) {
            throw new IllegalStateException("Result is already resolved");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Resolution note is required");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("Operator is required");
        }
        resolutionStatus = ResolutionStatus.RESOLVED;
        return ReconciliationResolutionEntity.resolve(
                id, note.strip(), operator.strip(), resolvedAt);
    }
}
