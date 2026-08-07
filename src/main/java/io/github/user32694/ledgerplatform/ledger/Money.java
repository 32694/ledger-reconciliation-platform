package io.github.user32694.ledgerplatform.ledger;

public record Money(long cents, String currency) {
    public Money {
        if (cents <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("Only CNY is supported");
    }

    public static Money cny(long cents) {
        return new Money(cents, "CNY");
    }
}
