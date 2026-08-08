package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

public record TransferCommand(
        String idempotencyKey, UUID payerAccountId, UUID payeeAccountId, long amountCents) {}
