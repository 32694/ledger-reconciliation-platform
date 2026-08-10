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

    @Column(name = "batch_job_instance_id")
    private Long batchJobInstanceId;

    @Column(name = "batch_job_execution_id")
    private Long batchJobExecutionId;

    @Column(name = "current_step", length = 64)
    private String currentStep;

    @Column(name = "processed_items", nullable = false)
    private int processedItems;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "restart_count", nullable = false)
    private int restartCount;

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
                errorMessage,
                batchJobInstanceId,
                batchJobExecutionId,
                currentStep,
                processedItems,
                totalItems,
                restartCount);
    }

    UUID id() {
        return id;
    }

    UUID batchId() {
        return batchId;
    }

    RunStatus status() {
        return status;
    }

    String requestedBy() {
        return requestedBy;
    }

    void start(Long jobInstanceId, Long jobExecutionId, Instant now) {
        if (status != RunStatus.QUEUED && status != RunStatus.FAILED) {
            throw new IllegalStateException("Run cannot start from " + status);
        }
        if (status == RunStatus.FAILED) {
            restartCount++;
        }
        status = RunStatus.RUNNING;
        batchJobInstanceId = jobInstanceId;
        batchJobExecutionId = jobExecutionId;
        startedAt = normalize(now);
        completedAt = null;
        errorMessage = null;
    }

    void start(Instant now) {
        start(null, null, now);
    }

    void setTotalItems(int totalItems) {
        this.totalItems = Math.max(0, totalItems);
        processedItems = Math.min(processedItems, this.totalItems);
    }

    void updateProgress(String step, int processedItems) {
        currentStep = step;
        this.processedItems = Math.min(totalItems, Math.max(this.processedItems, processedItems));
    }

    Long batchJobExecutionId() {
        return batchJobExecutionId;
    }

    int restartCount() {
        return restartCount;
    }

    void succeed(int matchedRows, int differenceRows, Instant now) {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException("Run cannot succeed from " + status);
        }
        status = RunStatus.SUCCEEDED;
        this.matchedRows = matchedRows;
        this.differenceRows = differenceRows;
        completedAt = normalize(now);
    }

    boolean fail(String message, Instant now) {
        if (status != RunStatus.QUEUED && status != RunStatus.RUNNING) {
            return false;
        }
        status = RunStatus.FAILED;
        errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 2000));
        completedAt = normalize(now);
        return true;
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
