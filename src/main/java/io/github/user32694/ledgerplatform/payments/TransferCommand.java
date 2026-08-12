package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

/** 客户钱包转账请求；付款方和收款方不能通过重复请求造成重复扣款。 */
public record TransferCommand(
        String idempotencyKey, UUID payerAccountId, UUID payeeAccountId, long amountCents) {}
