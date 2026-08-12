package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

/** 充值请求；幂等键用于安全重试，金额单位为分。 */
public record TopUpCommand(String idempotencyKey, UUID payeeAccountId, long amountCents) {}
