package io.github.user32694.ledgerplatform.payments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable keyset position for succeeded top-up pagination. */
public record PaymentPageCursor(Instant completedAt, UUID id) {
    public PaymentPageCursor {
        Objects.requireNonNull(completedAt, "completedAt is required");
        Objects.requireNonNull(id, "id is required");
    }
}
