package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.ledger.EntrySide;
import io.github.user32694.ledgerplatform.ledger.Journal;
import io.github.user32694.ledgerplatform.ledger.JournalEntry;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import io.github.user32694.ledgerplatform.ledger.LedgerBalanceLimitExceededException;
import io.github.user32694.ledgerplatform.ledger.LedgerInsufficientFundsException;
import io.github.user32694.ledgerplatform.ledger.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentProcessor {
    private static final String BALANCE_LIMIT_EXCEEDED = "BALANCE_LIMIT_EXCEEDED";
    private static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private final PaymentInstructionRepository repository;
    private final AccountsApi accountsApi;
    private final LedgerApi ledgerApi;

    PaymentProcessor(
            PaymentInstructionRepository repository,
            AccountsApi accountsApi,
            LedgerApi ledgerApi) {
        this.repository = repository;
        this.accountsApi = accountsApi;
        this.ledgerApi = ledgerApi;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PaymentInstructionEntity process(UUID paymentId) {
        var payment = repository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment instruction does not exist: " + paymentId));
        if (!"PENDING".equals(payment.status())) {
            return payment;
        }

        try {
            var amount = Money.cny(payment.amountCents());
            if ("TRANSFER".equals(payment.paymentType())) {
                var payerWalletId = accountsApi.get(payment.payerAccountId()).ledgerAccountId();
                var payeeWalletId = accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
                ledgerApi.post(Journal.create(payment.channelReference(), "TRANSFER", List.of(
                        new JournalEntry(payerWalletId, EntrySide.DEBIT, amount),
                        new JournalEntry(payeeWalletId, EntrySide.CREDIT, amount))));
            } else {
                var customerWalletId = accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
                var platformCashId = ledgerApi.createPlatformCashAccount().id();
                ledgerApi.post(Journal.create(payment.channelReference(), "TOP_UP", List.of(
                        new JournalEntry(platformCashId, EntrySide.DEBIT, amount),
                        new JournalEntry(customerWalletId, EntrySide.CREDIT, amount))));
            }
            payment.succeed(Instant.now());
        } catch (LedgerInsufficientFundsException exception) {
            throw new PaymentRejectedException(payment.id(), INSUFFICIENT_FUNDS);
        } catch (LedgerBalanceLimitExceededException exception) {
            throw new PaymentRejectedException(payment.id(), BALANCE_LIMIT_EXCEEDED);
        }
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PaymentInstructionEntity fail(UUID paymentId, String reason) {
        var payment = repository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment instruction does not exist: " + paymentId));
        if ("PENDING".equals(payment.status())) {
            payment.fail(reason, Instant.now());
        }
        return payment;
    }

    static final class PaymentRejectedException extends RuntimeException {
        private final UUID paymentId;
        private final String reason;

        PaymentRejectedException(UUID paymentId, String reason) {
            super(reason);
            this.paymentId = paymentId;
            this.reason = reason;
        }

        UUID paymentId() {
            return paymentId;
        }

        String reason() {
            return reason;
        }
    }
}
