package io.github.user32694.ledgerplatform.accounts;

import java.util.UUID;

public record CustomerAccountView(
        UUID id, String accountNumber, String ownerName, String status, UUID ledgerAccountId) {}
