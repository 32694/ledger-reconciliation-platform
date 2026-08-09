package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationCaseView(
        UUID id,
        UUID batchId,
        String batchFileName,
        UUID statementEntryId,
        UUID paymentId,
        String channelTransactionId,
        Long channelAmountCents,
        Long internalAmountCents,
        Long differenceAmountCents,
        Instant occurredAt,
        ResultType resultType,
        ResolutionStatus resolutionStatus,
        String assignedTo,
        Instant claimedAt,
        ResolutionCode resolutionCode,
        String resolutionNote,
        String resolvedBy,
        Instant resolvedAt) {}
