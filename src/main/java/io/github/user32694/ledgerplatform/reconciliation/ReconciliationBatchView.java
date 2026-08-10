package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record ReconciliationBatchView(
        UUID id,
        String sourceType,
        String fileName,
        String fileSha256,
        String channelCode,
        String channelDisplayName,
        UUID ruleVersionId,
        int ruleVersionNumber,
        long amountToleranceCents,
        int queryWindowHours,
        Instant periodStart,
        Instant periodEnd,
        BatchStatus status,
        int totalRows,
        int matchedRows,
        int differenceRows,
        String errorMessage,
        String createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
    public Instant queryStart() {
        requireQueryable();
        return periodStart.minus(queryWindowHours, ChronoUnit.HOURS);
    }

    public Instant queryEnd() {
        requireQueryable();
        return periodEnd.plus(queryWindowHours, ChronoUnit.HOURS);
    }

    private void requireQueryable() {
        if (status == BatchStatus.IMPORT_FAILED || periodStart == null || periodEnd == null) {
            throw new IllegalStateException("Batch has no successful import period");
        }
    }
}
