package io.github.user32694.ledgerplatform.payments;

import java.time.Instant;
import java.util.UUID;

/** 面向页面和对账模块的支付只读视图。金额单位为分，status 为状态机当前状态。 */
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
