package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.ledger.DuplicateBusinessReferenceException;
import io.github.user32694.ledgerplatform.ledger.EntrySide;
import io.github.user32694.ledgerplatform.ledger.Journal;
import io.github.user32694.ledgerplatform.ledger.JournalEntry;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import io.github.user32694.ledgerplatform.ledger.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentProcessor {
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
            var customerWalletId = accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
            var platformCashId = ledgerApi.createPlatformCashAccount().id();
            ledgerApi.post(Journal.create(payment.channelReference(), "TOP_UP", List.of(
                    new JournalEntry(
                            platformCashId,
                            EntrySide.DEBIT,
                            Money.cny(payment.amountCents())),
                    new JournalEntry(
                            customerWalletId,
                            EntrySide.CREDIT,
                            Money.cny(payment.amountCents())))));
            payment.succeed(Instant.now());
        } catch (IllegalArgumentException | DuplicateBusinessReferenceException exception) {
            throw new PaymentRejectedException(payment.id(), failureReason(exception));
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

    private static String failureReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Payment was rejected";
        }
        return message.length() <= 64 ? message : message.substring(0, 64);
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
