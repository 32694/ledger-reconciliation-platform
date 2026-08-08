package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reconciliation_batch", schema = "reconciliation")
class ReconciliationBatchEntity {
    @Id
    private UUID id;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String fileSha256;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BatchStatus status;

    @Column(nullable = false)
    private int totalRows;

    @Column(nullable = false)
    private int matchedRows;

    @Column(nullable = false)
    private int differenceRows;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ReconciliationBatchEntity() {}

    static ReconciliationBatchEntity imported(
            String fileName,
            String fileSha256,
            Instant periodStart,
            Instant periodEnd,
            int totalRows,
            String createdBy,
            Instant createdAt) {
        var batch = new ReconciliationBatchEntity();
        batch.id = UUID.randomUUID();
        batch.sourceType = "SYNTHETIC_CHANNEL";
        batch.fileName = fileName;
        batch.fileSha256 = fileSha256;
        batch.periodStart = normalize(periodStart);
        batch.periodEnd = normalize(periodEnd);
        batch.status = BatchStatus.IMPORTED;
        batch.totalRows = totalRows;
        batch.createdBy = createdBy;
        batch.createdAt = normalize(createdAt);
        return batch;
    }

    static ReconciliationBatchEntity importFailed(
            String fileName, String fileSha256, String errorMessage, String createdBy, Instant createdAt) {
        var batch = new ReconciliationBatchEntity();
        batch.id = UUID.randomUUID();
        batch.sourceType = "SYNTHETIC_CHANNEL";
        batch.fileName = fileName;
        batch.fileSha256 = fileSha256;
        batch.status = BatchStatus.IMPORT_FAILED;
        batch.errorMessage = errorMessage;
        batch.createdBy = createdBy;
        batch.createdAt = normalize(createdAt);
        return batch;
    }

    UUID id() {
        return id;
    }

    BatchStatus status() {
        return status;
    }

    Instant periodStart() {
        return periodStart;
    }

    Instant periodEnd() {
        return periodEnd;
    }

    int totalRows() {
        return totalRows;
    }

    void start(Instant now) {
        if (status != BatchStatus.IMPORTED && status != BatchStatus.RECONCILIATION_FAILED) {
            throw new IllegalStateException("Batch cannot start from " + status);
        }
        status = BatchStatus.RUNNING;
        startedAt = normalize(now);
        completedAt = null;
        errorMessage = null;
    }

    void complete(int matchedRows, int differenceRows, Instant now) {
        requireRunning();
        status = BatchStatus.COMPLETED;
        this.matchedRows = matchedRows;
        this.differenceRows = differenceRows;
        completedAt = normalize(now);
    }

    void failReconciliation(String message, Instant now) {
        requireRunning();
        status = BatchStatus.RECONCILIATION_FAILED;
        errorMessage = message;
        completedAt = normalize(now);
    }

    private void requireRunning() {
        if (status != BatchStatus.RUNNING) {
            throw new IllegalStateException("Batch is not running");
        }
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    ReconciliationBatchView toView() {
        return new ReconciliationBatchView(
                id,
                sourceType,
                fileName,
                fileSha256,
                periodStart,
                periodEnd,
                status,
                totalRows,
                matchedRows,
                differenceRows,
                errorMessage,
                createdBy,
                createdAt,
                startedAt,
                completedAt);
    }
}
