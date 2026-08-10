package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import org.springframework.stereotype.Component;

@Component
final class ReconciliationRuleMatcher {
    ReconciliationWorkResult process(
            ReconciliationWorkItem item, long amountToleranceCents) {
        if (item instanceof ReconciliationWorkItem.Statement statement) {
            return matchStatement(statement, amountToleranceCents);
        }
        var payment = (ReconciliationWorkItem.Payment) item;
        if (payment.consumed()) {
            return null;
        }
        return result(null, payment.paymentId(), ResultType.INTERNAL_ONLY);
    }

    private static ReconciliationWorkResult matchStatement(
            ReconciliationWorkItem.Statement statement, long amountToleranceCents) {
        if (statement.exactPayment().isEmpty()) {
            return result(statement.statementEntryId(), null, ResultType.CHANNEL_ONLY);
        }
        var payment = statement.exactPayment().orElseThrow();
        long difference = Math.max(statement.amountCents(), payment.amountCents())
                - Math.min(statement.amountCents(), payment.amountCents());
        var type = difference <= amountToleranceCents
                ? ResultType.MATCHED
                : ResultType.AMOUNT_MISMATCH;
        return result(statement.statementEntryId(), payment.paymentId(), type);
    }

    private static ReconciliationWorkResult result(
            java.util.UUID statementEntryId,
            java.util.UUID paymentId,
            ResultType resultType) {
        var status = resultType == ResultType.MATCHED
                ? ResolutionStatus.NOT_REQUIRED
                : ResolutionStatus.OPEN;
        return new ReconciliationWorkResult(statementEntryId, paymentId, resultType, status);
    }
}
