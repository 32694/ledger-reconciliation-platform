/**
 * 客户账户模块：管理面向运营人员展示的模拟客户钱包。
 *
 * <p>账户本身不保存可直接修改的余额；余额由 {@code ledger} 模块根据不可变账本分录计算。
 * 账户创建时会同时建立对应的客户钱包账务账户，保证业务账户与账务账户一一对应。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"ledger", "audit"})
package io.github.user32694.ledgerplatform.accounts;
