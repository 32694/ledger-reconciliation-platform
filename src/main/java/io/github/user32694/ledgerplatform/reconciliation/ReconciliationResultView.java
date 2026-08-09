package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationResultView(
        UUID id,
        UUID batchId,
        UUID statementEntryId,
        UUID paymentId,
        String channelTransactionId,
        Long channelAmountCents,
        Long internalAmountCents,
        Instant occurredAt,
        ResultType resultType,
        ResolutionStatus resolutionStatus,
        String assignedTo,
        Instant claimedAt,
        String resolutionNote,
        String resolvedBy,
        Instant resolvedAt) {}
