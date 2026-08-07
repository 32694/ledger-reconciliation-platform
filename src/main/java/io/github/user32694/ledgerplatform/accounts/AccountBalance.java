package io.github.user32694.ledgerplatform.accounts;

public record AccountBalance(long cents, String currency) {
    public AccountBalance {
        if (cents < 0) throw new IllegalArgumentException("Balance cannot be negative");
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("Only CNY is supported");
    }
}
