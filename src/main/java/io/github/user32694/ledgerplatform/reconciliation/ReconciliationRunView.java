package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunView(
        UUID id,
        UUID batchId,
        int attemptNumber,
        RunStatus status,
        String requestedBy,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        int matchedRows,
        int differenceRows,
        String errorMessage,
        Long batchJobInstanceId,
        Long batchJobExecutionId,
        String currentStep,
        int processedItems,
        int totalItems,
        int restartCount) {}
