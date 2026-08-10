package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

sealed interface ReconciliationWorkItem
        permits ReconciliationWorkItem.Statement, ReconciliationWorkItem.Payment {
    record Statement(
            UUID statementEntryId, long amountCents, Optional<Payment> exactPayment)
            implements ReconciliationWorkItem {
        public Statement {
            Objects.requireNonNull(statementEntryId, "statementEntryId is required");
            Objects.requireNonNull(exactPayment, "exactPayment is required");
            requirePositive(amountCents);
        }
    }

    record Payment(UUID paymentId, long amountCents, boolean consumed)
            implements ReconciliationWorkItem {
        public Payment {
            Objects.requireNonNull(paymentId, "paymentId is required");
            requirePositive(amountCents);
        }
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
    }
}
