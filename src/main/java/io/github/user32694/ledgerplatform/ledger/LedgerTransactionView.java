package io.github.user32694.ledgerplatform.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerTransactionView(
        UUID id, String businessReference, String transactionType, Instant occurredAt, long amountCents) {}
