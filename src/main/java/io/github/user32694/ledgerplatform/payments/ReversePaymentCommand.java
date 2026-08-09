package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

public record ReversePaymentCommand(
        String idempotencyKey, UUID originalPaymentId, String reason) {}
