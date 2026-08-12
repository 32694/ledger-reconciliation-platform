package io.github.user32694.ledgerplatform.accounts;

import java.util.UUID;

/** 面向页面和模块调用方的客户账户只读视图。 */
public record CustomerAccountView(
        UUID id, String accountNumber, String ownerName, String status, UUID ledgerAccountId) {}
