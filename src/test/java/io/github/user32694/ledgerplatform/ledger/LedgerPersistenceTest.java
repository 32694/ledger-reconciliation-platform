package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

@SpringBootTest
@ActiveProfiles("test")
@Sql(statements = {
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class LedgerPersistenceTest {
    @Autowired LedgerApi ledgerApi;

    @Test
    void postsAndReadsBalancedJournal() {
        var cash = ledgerApi.createPlatformCashAccount();
        var wallet = ledgerApi.createCustomerWallet("ACC-1001");
        var journal = Journal.create("TOPUP-1001", "TOP_UP", List.of(
                new JournalEntry(cash.id(), EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet.id(), EntrySide.CREDIT, Money.cny(5000))));

        var posted = ledgerApi.post(journal);

        assertThat(posted.businessReference()).isEqualTo("TOPUP-1001");
        assertThat(ledgerApi.walletBalance(wallet.id())).isEqualTo(5000);
    }

    @Test
    void returnsTheSamePlatformCashAccountWhenCreatedAgain() {
        var first = ledgerApi.createPlatformCashAccount();
        var second = ledgerApi.createPlatformCashAccount();

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void rejectsBalanceOverflowWithoutPersistingTheTransaction() {
        var cash = ledgerApi.createPlatformCashAccount();
        var wallet = ledgerApi.createCustomerWallet("ACC-MAX");
        ledgerApi.post(Journal.create("TOPUP-MAX", "TOP_UP", List.of(
                new JournalEntry(cash.id(), EntrySide.DEBIT, Money.cny(Long.MAX_VALUE)),
                new JournalEntry(wallet.id(), EntrySide.CREDIT, Money.cny(Long.MAX_VALUE)))));
        var overflow = Journal.create("TOPUP-OVERFLOW", "TOP_UP", List.of(
                new JournalEntry(cash.id(), EntrySide.DEBIT, Money.cny(1)),
                new JournalEntry(wallet.id(), EntrySide.CREDIT, Money.cny(1))));

        assertThatThrownBy(() -> ledgerApi.post(overflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported range");
        assertThat(ledgerApi.walletBalance(wallet.id())).isEqualTo(Long.MAX_VALUE);

        var source = ledgerApi.createCustomerWallet("ACC-SOURCE");
        var destination = ledgerApi.createCustomerWallet("ACC-DESTINATION");
        var retried = ledgerApi.post(Journal.create("TOPUP-OVERFLOW", "TRANSFER", List.of(
                new JournalEntry(source.id(), EntrySide.DEBIT, Money.cny(1)),
                new JournalEntry(destination.id(), EntrySide.CREDIT, Money.cny(1)))));

        assertThat(retried.businessReference()).isEqualTo("TOPUP-OVERFLOW");
    }
}
