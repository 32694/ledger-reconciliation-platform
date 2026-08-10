package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ReconciliationMatcher {
    record StatementEntrySnapshot(
            UUID id, int lineNumber, String channelTransactionId, long amountCents, Instant occurredAt) {}
}
