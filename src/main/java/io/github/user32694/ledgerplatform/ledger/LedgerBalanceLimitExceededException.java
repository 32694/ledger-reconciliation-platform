package io.github.user32694.ledgerplatform.ledger;

public final class LedgerBalanceLimitExceededException extends IllegalArgumentException {
    private static final String MESSAGE = "Ledger balance exceeds supported range";

    public LedgerBalanceLimitExceededException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
