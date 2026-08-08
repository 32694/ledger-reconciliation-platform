package io.github.user32694.ledgerplatform.payments;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency key was already used for a different request");
    }
}
