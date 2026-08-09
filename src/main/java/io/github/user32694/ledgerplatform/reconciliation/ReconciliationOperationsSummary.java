package io.github.user32694.ledgerplatform.reconciliation;

public record ReconciliationOperationsSummary(
        Double completedMatchRate,
        long openCount,
        long claimedCount,
        long failedRunCount) {}
