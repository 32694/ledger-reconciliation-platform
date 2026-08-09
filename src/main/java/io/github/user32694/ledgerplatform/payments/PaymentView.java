package io.github.user32694.ledgerplatform.payments;

import java.time.Instant;
import java.util.UUID;

public record PaymentView(
        UUID id,
        String channelReference,
        String type,
        UUID payerAccountId,
        UUID payeeAccountId,
        long amountCents,
        String status,
        String failureReason,
        UUID originalPaymentId,
        String operationReason,
        Instant occurredAt) {
    public PaymentView(
            UUID id,
            String channelReference,
            String type,
            UUID payerAccountId,
            UUID payeeAccountId,
            long amountCents,
            String status,
            String failureReason,
            Instant occurredAt) {
        this(
                id,
                channelReference,
                type,
                payerAccountId,
                payeeAccountId,
                amountCents,
                status,
                failureReason,
                null,
                null,
                occurredAt);
    }

    public PaymentView(
            UUID id,
            String channelReference,
            String type,
            long amountCents,
            String status,
            String failureReason,
            Instant occurredAt) {
        this(
                id,
                channelReference,
                type,
                null,
                null,
                amountCents,
                status,
                failureReason,
                null,
                null,
                occurredAt);
    }
}
