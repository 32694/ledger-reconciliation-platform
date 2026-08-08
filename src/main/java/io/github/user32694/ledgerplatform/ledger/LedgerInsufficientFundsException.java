package io.github.user32694.ledgerplatform.ledger;

public class LedgerInsufficientFundsException extends RuntimeException {
    public LedgerInsufficientFundsException() {
        super("Customer wallet has insufficient funds");
    }
}
