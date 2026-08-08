package io.github.user32694.ledgerplatform.ledger.internal;

import io.github.user32694.ledgerplatform.ledger.AccountType;
import io.github.user32694.ledgerplatform.ledger.DuplicateBusinessReferenceException;
import io.github.user32694.ledgerplatform.ledger.EntrySide;
import io.github.user32694.ledgerplatform.ledger.Journal;
import io.github.user32694.ledgerplatform.ledger.LedgerAccountView;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import io.github.user32694.ledgerplatform.ledger.LedgerBalanceLimitExceededException;
import io.github.user32694.ledgerplatform.ledger.LedgerTransactionView;
import io.github.user32694.ledgerplatform.ledger.PostedJournal;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerService implements LedgerApi {
    private static final String PLATFORM_CASH = "PLATFORM_CASH";
    private static final String BUSINESS_REFERENCE_CONSTRAINT =
            "uk_ledger_transaction_business_reference";

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
        accountRepository.insertPlatformCashAccount(
                UUID.randomUUID(), PLATFORM_CASH, Instant.now());
        var account = accountRepository.findByOwnerReference(PLATFORM_CASH)
                .orElseThrow(() -> new IllegalStateException("Platform cash account was not created"));
        if (account.accountType() != AccountType.ASSET) {
            throw new IllegalStateException("Platform cash account must have account type ASSET");
        }
        return toView(account);
    }

    @Override
    @Transactional
    public LedgerAccountView createCustomerWallet(String customerAccountNumber) {
        requireOwnerReference(customerAccountNumber);
        if (PLATFORM_CASH.equals(customerAccountNumber)) {
            throw new IllegalArgumentException("Customer account number is reserved for platform cash");
        }
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

        List<UUID> accountIds = journal.entries().stream()
                .map(entry -> entry.ledgerAccountId())
                .distinct()
                .sorted()
                .toList();
        List<LedgerAccountEntity> lockedAccounts = accountRepository.findAllByIdForUpdate(accountIds);
        if (lockedAccounts.size() != accountIds.size()) {
            throw new IllegalArgumentException("Journal contains a ledger account that does not exist");
        }
        Map<UUID, LedgerAccountEntity> accountsById = new HashMap<>();
        for (var account : lockedAccounts) {
            accountsById.put(account.id(), account);
        }

        Map<UUID, Long> deltas = new HashMap<>();
        for (var entry : journal.entries()) {
            var account = accountsById.get(entry.ledgerAccountId());
            long delta = signedDelta(account.accountType(), entry.side(), entry.money().cents());
            try {
                deltas.put(account.id(), Math.addExact(deltas.getOrDefault(account.id(), 0L), delta));
            } catch (ArithmeticException exception) {
                throw new LedgerBalanceLimitExceededException(exception);
            }
        }
        for (var account : lockedAccounts) {
            try {
                Math.addExact(currentBalance(account), deltas.getOrDefault(account.id(), 0L));
            } catch (ArithmeticException exception) {
                throw new LedgerBalanceLimitExceededException(exception);
            }
        }

        Instant occurredAt = Instant.now();
        var transaction = new LedgerTransactionEntity(
                UUID.randomUUID(), journal.businessReference(), journal.type(), occurredAt);
        for (var entry : journal.entries()) {
            var account = accountsById.get(entry.ledgerAccountId());
            transaction.addEntry(new LedgerEntryEntity(
                    UUID.randomUUID(), transaction, account, entry.side(), entry.money().cents(), occurredAt));
        }

        try {
            var saved = transactionRepository.saveAndFlush(transaction);
            return new PostedJournal(saved.id(), saved.businessReference());
        } catch (DataIntegrityViolationException exception) {
            if (isBusinessReferenceDuplicate(exception)) {
                throw new DuplicateBusinessReferenceException(journal.businessReference(), exception);
            }
            throw exception;
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
        return currentBalance(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerTransactionView> findRecentTransactions(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        return transactionRepository
                .findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(0, limit))
                .stream()
                .map(transaction -> new LedgerTransactionView(
                        transaction.id(),
                        transaction.businessReference(),
                        transaction.transactionType(),
                        transaction.occurredAt(),
                        transaction.entries().stream()
                                .filter(entry -> entry.side() == EntrySide.DEBIT)
                                .mapToLong(LedgerEntryEntity::amountCents)
                                .sum()))
                .toList();
    }

    private LedgerAccountView toView(LedgerAccountEntity account) {
        return new LedgerAccountView(account.id(), account.ownerReference(), account.accountType());
    }

    private long currentBalance(LedgerAccountEntity account) {
        BigDecimal liabilityBalance = transactionRepository.liabilityBalance(account.id());
        BigDecimal balance = account.accountType() == AccountType.LIABILITY
                ? liabilityBalance
                : liabilityBalance.negate();
        try {
            return balance.longValueExact();
        } catch (ArithmeticException exception) {
            throw new LedgerBalanceLimitExceededException(exception);
        }
    }

    private static long signedDelta(AccountType accountType, EntrySide side, long amount) {
        boolean increasesBalance = accountType == AccountType.ASSET
                ? side == EntrySide.DEBIT
                : side == EntrySide.CREDIT;
        return increasesBalance ? amount : -amount;
    }

    private static boolean isBusinessReferenceDuplicate(DataIntegrityViolationException exception) {
        boolean uniqueViolation = false;
        boolean namedConstraint = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                uniqueViolation = true;
            }
            if (cause.getMessage() != null
                    && cause.getMessage().contains(BUSINESS_REFERENCE_CONSTRAINT)) {
                namedConstraint = true;
            }
        }
        return uniqueViolation && namedConstraint;
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
