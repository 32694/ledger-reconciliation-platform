package io.github.user32694.ledgerplatform.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一笔完整不可变账务交易及其借贷分录。 */
public record LedgerTransactionDetailsView(
        UUID id,
        String businessReference,
        String transactionType,
        Instant occurredAt,
        List<LedgerEntryView> entries) {
    public LedgerTransactionDetailsView {
        entries = List.copyOf(entries);
    }
}
