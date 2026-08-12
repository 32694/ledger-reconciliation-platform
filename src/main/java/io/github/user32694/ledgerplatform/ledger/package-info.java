/**
 * 账本模块：保存不可变、借贷平衡的 ledger transaction 和 ledger entry。
 *
 * <p>这是资金一致性的最底层边界。上层支付模块只能提交一个完整 journal，不能直接修改余额；
 * 可用余额通过已过账分录实时计算，并在入账时使用 PostgreSQL 行锁控制并发扣款。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package io.github.user32694.ledgerplatform.ledger;
