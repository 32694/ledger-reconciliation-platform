package io.github.user32694.ledgerplatform.ledger;

import java.util.Objects;
import java.util.UUID;

/** Journal 中的一条借方或贷方分录。 */
public record JournalEntry(UUID ledgerAccountId, EntrySide side, Money money) {
    public JournalEntry {
        // 分录必须引用真实账务账户，并且必须带方向和正金额。
        Objects.requireNonNull(ledgerAccountId);
        Objects.requireNonNull(side);
        Objects.requireNonNull(money);
    }
}
