package io.github.user32694.ledgerplatform.ledger;

/**
 * 以最小货币单位表示的金额。
 *
 * <p>系统统一保存为分，避免浮点误差。当前演示只支持人民币 CNY，且不允许零或负数金额。
 */
public record Money(long cents, String currency) {
    public Money {
        // 在值对象构造时完成校验，让所有调用方共享同一套金额规则。
        if (cents <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("Only CNY is supported");
    }

    /** 创建一个人民币金额，参数单位为分。 */
    public static Money cny(long cents) {
        return new Money(cents, "CNY");
    }
}
