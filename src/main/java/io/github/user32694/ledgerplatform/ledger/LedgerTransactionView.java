package io.github.user32694.ledgerplatform.ledger;

import java.time.Instant;
import java.util.UUID;

/** 页面展示用的不可变 ledger transaction 摘要。 */
public record LedgerTransactionView(
        UUID id, String businessReference, String transactionType, Instant occurredAt, long amountCents) {}
