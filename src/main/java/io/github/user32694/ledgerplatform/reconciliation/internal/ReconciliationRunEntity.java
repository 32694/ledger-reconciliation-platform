package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
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
@Table(name = "reconciliation_run", schema = "reconciliation")
class ReconciliationRunEntity {
    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "matched_rows", nullable = false)
    private int matchedRows;

    @Column(name = "difference_rows", nullable = false)
    private int differenceRows;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private long version;

    protected ReconciliationRunEntity() {}

    static ReconciliationRunEntity queued(
            UUID batchId, int attemptNumber, String requestedBy, Instant requestedAt) {
        var run = new ReconciliationRunEntity();
        run.id = UUID.randomUUID();
        run.batchId = batchId;
        run.attemptNumber = attemptNumber;
        run.status = RunStatus.QUEUED;
        run.requestedBy = requestedBy;
        run.requestedAt = normalize(requestedAt);
        return run;
    }

    ReconciliationRunView toView() {
        return new ReconciliationRunView(
                id,
                batchId,
                attemptNumber,
                status,
                requestedBy,
                requestedAt,
                startedAt,
                completedAt,
                matchedRows,
                differenceRows,
                errorMessage);
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
