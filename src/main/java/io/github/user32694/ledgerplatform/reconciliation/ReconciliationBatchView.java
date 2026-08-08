package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationBatchView(
        UUID id,
        String sourceType,
        String fileName,
        String fileSha256,
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
        Instant completedAt) {}
