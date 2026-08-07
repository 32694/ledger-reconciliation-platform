package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
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
}
