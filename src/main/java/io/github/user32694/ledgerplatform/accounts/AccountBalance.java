package io.github.user32694.ledgerplatform.accounts;

/** 从账本分录计算出的客户可用余额；余额不是账户表里的可编辑字段。 */
public record AccountBalance(long cents, String currency) {
    public AccountBalance {
        if (cents < 0) throw new IllegalArgumentException("Balance cannot be negative");
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("Only CNY is supported");
    }
}
