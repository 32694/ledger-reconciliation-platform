package io.github.user32694.ledgerplatform.ledger;

import java.util.UUID;

public interface LedgerApi {
    LedgerAccountView createPlatformCashAccount();
    LedgerAccountView createCustomerWallet(String customerAccountNumber);
    PostedJournal post(Journal journal);
    long walletBalance(UUID ledgerAccountId);
}
