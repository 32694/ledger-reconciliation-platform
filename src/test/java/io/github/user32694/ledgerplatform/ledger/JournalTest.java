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

    @Test
    void rejectsAmountsThatOverflowTheSupportedRange() {
        assertThatThrownBy(() -> Journal.create("TOPUP-4", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(Long.MAX_VALUE)),
                new JournalEntry(wallet, EntrySide.DEBIT, Money.cny(Long.MAX_VALUE)),
                new JournalEntry(UUID.randomUUID(), EntrySide.DEBIT, Money.cny(2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported range");
    }

    @Test
    void rejectsInvalidTypes() {
        List<JournalEntry> entries = List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(5000)));

        assertThatThrownBy(() -> Journal.create("TOPUP-5", null, entries))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Journal.create("TOPUP-5", " ", entries))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Journal.create("TOPUP-5", "UNKNOWN", entries))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsReversePaymentTypes() {
        List<JournalEntry> entries = List.of(
                new JournalEntry(wallet, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(cash, EntrySide.CREDIT, Money.cny(5000)));

        assertThat(Journal.create("REFUND-1", "REFUND", entries).type())
                .isEqualTo("REFUND");
        assertThat(Journal.create("REVERSAL-1", "REVERSAL", entries).type())
                .isEqualTo("REVERSAL");
    }

    @Test
    void rejectsBusinessReferenceLongerThan128Characters() {
        assertThatThrownBy(() -> Journal.create("R".repeat(129), "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(5000)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBusinessReferenceAt128Characters() {
        String businessReference = "R".repeat(128);

        Journal journal = Journal.create(businessReference, "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(5000))));

        assertThat(journal.businessReference()).isEqualTo(businessReference);
    }
}
