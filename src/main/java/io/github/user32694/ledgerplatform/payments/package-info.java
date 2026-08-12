/**
 * 支付模块：提供充值、账户转账、退款和转账冲正。
 *
 * <p>支付指令先以幂等键落库，再由处理器推进显式状态机。成功状态只在账本 journal 成功入账后产生；
 * 原支付和反向支付均保留，反向操作不会覆盖原始事实。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"accounts", "ledger", "audit", "messaging"})
package io.github.user32694.ledgerplatform.payments;
