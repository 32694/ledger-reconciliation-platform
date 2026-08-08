package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

public record TopUpCommand(String idempotencyKey, UUID payeeAccountId, long amountCents) {}
