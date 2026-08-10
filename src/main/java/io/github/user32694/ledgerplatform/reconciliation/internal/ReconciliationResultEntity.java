package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionCode;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    @Column(name = "assigned_to", length = 128)
    private String assignedTo;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ReconciliationResultEntity() {}

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

    String assignedTo() {
        return assignedTo;
    }

    Instant claimedAt() {
        return claimedAt;
    }

    boolean claim(String operator, Instant now) {
        String actor = requireOperator(operator);
        if (resultType == ResultType.MATCHED) {
            throw new IllegalStateException("MATCHED result cannot be claimed");
        }
        if (resolutionStatus == ResolutionStatus.RESOLVED) {
            throw new IllegalStateException("RESOLVED result cannot be claimed");
        }
        if (resolutionStatus == ResolutionStatus.CLAIMED) {
            if (assignedTo.equals(actor)) {
                return false;
            }
            throw new IllegalStateException("Result is assigned to another operator");
        }
        if (resolutionStatus != ResolutionStatus.OPEN) {
            throw new IllegalStateException("Result cannot be claimed from " + resolutionStatus);
        }
        assignedTo = actor;
        claimedAt = normalize(now);
        resolutionStatus = ResolutionStatus.CLAIMED;
        return true;
    }

    void release(String operator) {
        String actor = requireOperator(operator);
        requireClaimedBy(actor);
        resolutionStatus = ResolutionStatus.OPEN;
        assignedTo = null;
        claimedAt = null;
    }

    ReconciliationResolutionEntity resolve(
            ResolutionCode resolutionCode, String note, String operator, Instant resolvedAt) {
        if (resolutionCode == null) {
            throw new IllegalArgumentException("Resolution code is required");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Resolution note is required");
        }
        String actor = requireOperator(operator);
        requireClaimedBy(actor);
        resolutionStatus = ResolutionStatus.RESOLVED;
        return ReconciliationResolutionEntity.resolve(
                id, resolutionCode, note.strip(), actor, resolvedAt);
    }

    private void requireClaimedBy(String actor) {
        if (resultType == ResultType.MATCHED) {
            throw new IllegalStateException("MATCHED result does not require resolution");
        }
        if (resolutionStatus != ResolutionStatus.CLAIMED) {
            throw new IllegalStateException("Result cannot transition from " + resolutionStatus);
        }
        if (!assignedTo.equals(actor)) {
            throw new IllegalStateException("Result is assigned to another operator");
        }
    }

    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("Operator is required");
        }
        return operator.strip();
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
