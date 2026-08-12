package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

/** 反向支付请求；原交易保留不变，反向交易使用新的幂等键和审计记录。 */
public record ReversePaymentCommand(
        String idempotencyKey, UUID originalPaymentId, String reason) {}
