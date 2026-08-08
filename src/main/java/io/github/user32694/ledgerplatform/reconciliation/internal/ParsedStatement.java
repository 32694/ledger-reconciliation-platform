package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.time.Instant;
import java.util.List;

record ParsedStatement(List<ParsedStatement.Entry> entries, Instant periodStart, Instant periodEnd) {
    ParsedStatement {
        entries = List.copyOf(entries);
    }

    record Entry(int lineNumber, String channelTransactionId, long amountCents, Instant occurredAt) {}
}
