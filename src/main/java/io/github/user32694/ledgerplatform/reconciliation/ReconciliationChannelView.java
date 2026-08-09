package io.github.user32694.ledgerplatform.reconciliation;

import java.util.UUID;

public record ReconciliationChannelView(
        UUID id, String code, String displayName, boolean active, long version) {}
