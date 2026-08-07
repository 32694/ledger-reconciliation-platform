package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalTest {
    private final UUID cash = UUID.randomUUID();
    private final UUID wallet = UUID.randomUUID();

    @Test
    void acceptsBalancedEntries() {
        Journal journal = Journal.create("TOPUP-1", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(5000))));

        assertThat(journal.entries()).hasSize(2);
        assertThat(journal.totalDebits()).isEqualTo(5000);
        assertThat(journal.totalCredits()).isEqualTo(5000);
    }

    @Test
    void rejectsUnbalancedEntries() {
        assertThatThrownBy(() -> Journal.create("TOPUP-2", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(4900)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void rejectsFewerThanTwoEntries() {
        assertThatThrownBy(() -> Journal.create("TOPUP-3", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
