package io.github.user32694.ledgerplatform.reconciliation;

import java.util.UUID;

public record ReconciliationCaseProgress(
        UUID batchId,
        long resolvedCount,
        long totalCount) {}
