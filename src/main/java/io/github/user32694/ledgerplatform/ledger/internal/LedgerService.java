package io.github.user32694.ledgerplatform.ledger.internal;

import io.github.user32694.ledgerplatform.ledger.AccountType;
import io.github.user32694.ledgerplatform.ledger.DuplicateBusinessReferenceException;
import io.github.user32694.ledgerplatform.ledger.Journal;
import io.github.user32694.ledgerplatform.ledger.LedgerAccountView;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import io.github.user32694.ledgerplatform.ledger.PostedJournal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerService implements LedgerApi {
    private static final String PLATFORM_CASH = "PLATFORM_CASH";

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;

    LedgerService(
            LedgerAccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional
    public LedgerAccountView createPlatformCashAccount() {
        return accountRepository.findByOwnerReference(PLATFORM_CASH)
                .map(this::toView)
                .orElseGet(() -> toView(accountRepository.save(new LedgerAccountEntity(
                        UUID.randomUUID(), PLATFORM_CASH, AccountType.ASSET, Instant.now()))));
    }

    @Override
    @Transactional
    public LedgerAccountView createCustomerWallet(String customerAccountNumber) {
        requireOwnerReference(customerAccountNumber);
        return toView(accountRepository.save(new LedgerAccountEntity(
                UUID.randomUUID(), customerAccountNumber, AccountType.LIABILITY, Instant.now())));
    }

    @Override
    @Transactional
    public PostedJournal post(Journal journal) {
        Objects.requireNonNull(journal, "Journal is required");
        if (transactionRepository.existsByBusinessReference(journal.businessReference())) {
            throw new DuplicateBusinessReferenceException(journal.businessReference());
        }

        Instant occurredAt = Instant.now();
        var transaction = new LedgerTransactionEntity(
                UUID.randomUUID(), journal.businessReference(), journal.type(), occurredAt);
        for (var entry : journal.entries()) {
            var account = accountRepository.findById(entry.ledgerAccountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Ledger account does not exist: " + entry.ledgerAccountId()));
            transaction.addEntry(new LedgerEntryEntity(
                    UUID.randomUUID(), transaction, account, entry.side(), entry.money().cents(), occurredAt));
        }

        try {
            var saved = transactionRepository.saveAndFlush(transaction);
            return new PostedJournal(saved.id(), saved.businessReference());
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateBusinessReferenceException(journal.businessReference(), exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long walletBalance(UUID ledgerAccountId) {
        Objects.requireNonNull(ledgerAccountId, "Ledger account id is required");
        var account = accountRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ledger account does not exist: " + ledgerAccountId));
        if (account.accountType() != AccountType.LIABILITY) {
            throw new IllegalArgumentException("Ledger account is not a customer wallet");
        }
        return transactionRepository.liabilityBalance(ledgerAccountId);
    }

    private LedgerAccountView toView(LedgerAccountEntity account) {
        return new LedgerAccountView(account.id(), account.ownerReference(), account.accountType());
    }

    private static void requireOwnerReference(String ownerReference) {
        if (ownerReference == null || ownerReference.isBlank()) {
            throw new IllegalArgumentException("Customer account number is required");
        }
        if (ownerReference.length() > 64) {
            throw new IllegalArgumentException("Customer account number must not exceed 64 characters");
        }
    }
}
