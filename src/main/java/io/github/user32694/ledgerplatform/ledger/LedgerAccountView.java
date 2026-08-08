package io.github.user32694.ledgerplatform.ledger;

import java.util.UUID;

public record LedgerAccountView(UUID id, String ownerReference, AccountType accountType) {}
