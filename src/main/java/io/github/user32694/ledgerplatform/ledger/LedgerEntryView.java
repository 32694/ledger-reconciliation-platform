package io.github.user32694.ledgerplatform.ledger;

import java.time.Instant;
import java.util.UUID;

/** 对账和只读 API 使用的单条账本分录视图，不暴露 JPA 实体。 */
public record LedgerEntryView(
        UUID id,
        UUID ledgerAccountId,
        String accountReference,
        AccountType accountType,
        EntrySide side,
        long amountCents,
        Instant createdAt) {}
