package io.github.user32694.ledgerplatform.reconciliation;

import java.util.UUID;

public record ReconciliationOperationsSummary(
        UUID latestCompletedBatchId,
        Double completedMatchRate,
        long openCount,
        long claimedCount,
        long failedRunCount) {}
