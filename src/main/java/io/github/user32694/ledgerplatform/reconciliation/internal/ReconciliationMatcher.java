package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class ReconciliationMatcher {
    List<ResultDraft> match(
            List<StatementEntrySnapshot> entries, List<PaymentView> candidates) {
        Map<String, PaymentView> paymentsByReference = new HashMap<>();
        for (PaymentView payment : candidates) {
            paymentsByReference.put(payment.channelReference(), payment);
        }
        Set<UUID> consumedPayments = new HashSet<>();
        List<ResultDraft> drafts = new ArrayList<>();
        for (StatementEntrySnapshot entry : entries) {
            PaymentView payment = paymentsByReference.get(entry.channelTransactionId());
            if (payment == null) {
                drafts.add(new ResultDraft(
                        entry.id(), null, ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN,
                        entry.occurredAt(), entry.channelTransactionId()));
            } else if (payment.amountCents() != entry.amountCents()) {
                consumedPayments.add(payment.id());
                drafts.add(new ResultDraft(
                        entry.id(), payment.id(), ResultType.AMOUNT_MISMATCH, ResolutionStatus.OPEN,
                        entry.occurredAt(), entry.channelTransactionId()));
            } else {
                consumedPayments.add(payment.id());
                drafts.add(new ResultDraft(
                        entry.id(), payment.id(), ResultType.MATCHED, ResolutionStatus.NOT_REQUIRED,
                        entry.occurredAt(), entry.channelTransactionId()));
            }
        }
        for (PaymentView payment : candidates) {
            if (!consumedPayments.contains(payment.id())) {
                drafts.add(new ResultDraft(
                        null, payment.id(), ResultType.INTERNAL_ONLY, ResolutionStatus.OPEN,
                        payment.occurredAt(), payment.channelReference()));
            }
        }
        return drafts.stream()
                .sorted(Comparator
                        .comparingInt((ResultDraft draft) -> draft.resultType() == ResultType.MATCHED ? 1 : 0)
                        .thenComparing(ResultDraft::occurredAt)
                        .thenComparing(ResultDraft::sortReference, Comparator.nullsLast(String::compareTo))
                        .thenComparing(draft -> draft.paymentId(), Comparator.nullsLast(UUID::compareTo)))
                .toList();
    }

    record StatementEntrySnapshot(
            UUID id, int lineNumber, String channelTransactionId, long amountCents, Instant occurredAt) {}

    record ResultDraft(
            UUID statementEntryId,
            UUID paymentId,
            ResultType resultType,
            ResolutionStatus resolutionStatus,
            Instant occurredAt,
            String sortReference) {}
}
