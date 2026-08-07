package io.github.user32694.ledgerplatform.accounts.internal;

import io.github.user32694.ledgerplatform.accounts.AccountBalance;
import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.accounts.CustomerAccountView;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountsService implements AccountsApi {
    private static final String ACCOUNT_NUMBER_PREFIX = "ACC-";

    private final CustomerAccountRepository accountRepository;
    private final LedgerApi ledgerApi;

    AccountsService(CustomerAccountRepository accountRepository, LedgerApi ledgerApi) {
        this.accountRepository = accountRepository;
        this.ledgerApi = ledgerApi;
    }

    @Override
    @Transactional
    public CustomerAccountView create(String ownerName) {
        String normalizedOwnerName = requireOwnerName(ownerName);
        UUID accountId = UUID.randomUUID();
        String accountNumber = ACCOUNT_NUMBER_PREFIX
                + accountId.toString().replace("-", "").substring(0, 28);
        var wallet = ledgerApi.createCustomerWallet(accountNumber);
        Instant createdAt = Instant.now();
        var account = accountRepository.save(new CustomerAccountEntity(
                accountId, accountNumber, normalizedOwnerName, wallet.id(), createdAt));
        return toView(account);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerAccountView get(UUID accountId) {
        return toView(findAccount(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerAccountView> findAll() {
        return accountRepository.findAllByOrderByAccountNumberAsc().stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountBalance balance(UUID accountId) {
        var account = findAccount(accountId);
        return new AccountBalance(ledgerApi.walletBalance(account.ledgerAccountId()), "CNY");
    }

    private CustomerAccountEntity findAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account id is required");
        }
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer account does not exist: " + accountId));
    }

    private CustomerAccountView toView(CustomerAccountEntity account) {
        return new CustomerAccountView(
                account.id(),
                account.accountNumber(),
                account.ownerName(),
                account.status(),
                account.ledgerAccountId());
    }

    private static String requireOwnerName(String ownerName) {
        if (ownerName == null) {
            throw new IllegalArgumentException("Owner name is required");
        }
        String normalizedOwnerName = ownerName.trim();
        if (normalizedOwnerName.length() < 2 || normalizedOwnerName.length() > 100) {
            throw new IllegalArgumentException("Owner name must be between 2 and 100 characters");
        }
        return normalizedOwnerName;
    }
}
