package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.test.context.jdbc.SqlMergeMode.MergeMode;

@SpringBootTest
@ActiveProfiles("test")
@SqlMergeMode(MergeMode.MERGE)
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
    void rejectsThePlatformCashOwnerReferenceForCustomerWallets() {
        assertThatThrownBy(() -> ledgerApi.createCustomerWallet("PLATFORM_CASH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    @Sql(statements = """
            INSERT INTO ledger.ledger_account
                (id, owner_ref, account_type, currency, created_at)
            VALUES
                ('00000000-0000-0000-0000-000000000001', 'PLATFORM_CASH', 'LIABILITY', 'CNY', CURRENT_TIMESTAMP);
            """)
    void rejectsAnExistingNonAssetPlatformCashAccount() {
        assertThatThrownBy(() -> ledgerApi.createPlatformCashAccount())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ASSET");
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
                .isInstanceOf(LedgerBalanceLimitExceededException.class)
                .hasMessage("Ledger balance exceeds supported range");
        assertThat(ledgerApi.walletBalance(wallet.id())).isEqualTo(Long.MAX_VALUE);

        var destination = ledgerApi.createCustomerWallet("ACC-DESTINATION");
        var retried = ledgerApi.post(Journal.create("TOPUP-OVERFLOW", "TRANSFER", List.of(
                new JournalEntry(wallet.id(), EntrySide.DEBIT, Money.cny(1)),
                new JournalEntry(destination.id(), EntrySide.CREDIT, Money.cny(1)))));

        assertThat(retried.businessReference()).isEqualTo("TOPUP-OVERFLOW");
    }

    @Test
    void rejectsTransferThatWouldMakeACustomerWalletNegative() {
        var cash = ledgerApi.createPlatformCashAccount();
        var payer = ledgerApi.createCustomerWallet("ACC-FUNDED-PAYER");
        var payee = ledgerApi.createCustomerWallet("ACC-TRANSFER-PAYEE");
        ledgerApi.post(Journal.create("TOPUP-FUNDED-PAYER", "TOP_UP", List.of(
                new JournalEntry(cash.id(), EntrySide.DEBIT, Money.cny(500)),
                new JournalEntry(payer.id(), EntrySide.CREDIT, Money.cny(500)))));

        var transfer = Journal.create("TRANSFER-INSUFFICIENT", "TRANSFER", List.of(
                new JournalEntry(payer.id(), EntrySide.DEBIT, Money.cny(600)),
                new JournalEntry(payee.id(), EntrySide.CREDIT, Money.cny(600))));

        assertThatThrownBy(() -> ledgerApi.post(transfer))
                .isInstanceOf(LedgerInsufficientFundsException.class)
                .hasMessage("Customer wallet has insufficient funds");
        assertThat(ledgerApi.walletBalance(payer.id())).isEqualTo(500);
        assertThat(ledgerApi.walletBalance(payee.id())).isZero();
        assertThat(ledgerApi.findRecentTransactions(10))
                .extracting(LedgerTransactionView::businessReference)
                .doesNotContain("TRANSFER-INSUFFICIENT");
    }

    @Test
    @Sql(statements = """
            INSERT INTO ledger.ledger_account
                (id, owner_ref, account_type, currency, created_at)
            VALUES
                ('00000000-0000-0000-0000-000000000010', 'ACC-SQL-OVERFLOW', 'LIABILITY', 'CNY', CURRENT_TIMESTAMP);
            INSERT INTO ledger.ledger_transaction
                (id, business_reference, transaction_type, occurred_at)
            VALUES
                ('00000000-0000-0000-0000-000000000011', 'SQL-OVERFLOW-1', 'TOP_UP', CURRENT_TIMESTAMP),
                ('00000000-0000-0000-0000-000000000012', 'SQL-OVERFLOW-2', 'TOP_UP', CURRENT_TIMESTAMP);
            INSERT INTO ledger.ledger_entry
                (id, transaction_id, ledger_account_id, side, amount_cents, created_at)
            VALUES
                ('00000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000011',
                 '00000000-0000-0000-0000-000000000010', 'CREDIT', 9223372036854775807, CURRENT_TIMESTAMP),
                ('00000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000012',
                 '00000000-0000-0000-0000-000000000010', 'CREDIT', 1, CURRENT_TIMESTAMP);
            """)
    void rejectsPersistedBalanceOutsideLongRangeWithoutExposingAccountId() {
        assertThatThrownBy(() -> ledgerApi.walletBalance(
                UUID.fromString("00000000-0000-0000-0000-000000000010")))
                .isInstanceOf(LedgerBalanceLimitExceededException.class)
                .hasMessage("Ledger balance exceeds supported range");
    }
}
