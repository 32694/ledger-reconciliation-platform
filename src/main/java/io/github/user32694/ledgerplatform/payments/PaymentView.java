package io.github.user32694.ledgerplatform.payments;

import java.time.Instant;
import java.util.UUID;

public record PaymentView(
        UUID id,
        String channelReference,
        String type,
        long amountCents,
        String status,
        String failureReason,
        Instant occurredAt) {}
