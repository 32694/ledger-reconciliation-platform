package io.github.user32694.ledgerplatform.ledger;

import java.util.UUID;

/** 账务账户的只读视图，供账户和支付模块建立业务映射。 */
public record LedgerAccountView(UUID id, String ownerReference, AccountType accountType) {}
