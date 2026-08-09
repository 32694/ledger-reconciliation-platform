package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationCaseEventView(
        UUID id,
        UUID resultId,
        String action,
        String actor,
        ResolutionCode resolutionCode,
        String note,
        Instant createdAt) {}
