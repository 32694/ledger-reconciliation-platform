package io.github.user32694.ledgerplatform.ledger;

import java.util.Objects;
import java.util.UUID;

public record JournalEntry(UUID ledgerAccountId, EntrySide side, Money money) {
    public JournalEntry {
        Objects.requireNonNull(ledgerAccountId);
        Objects.requireNonNull(side);
        Objects.requireNonNull(money);
    }
}
