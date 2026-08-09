package io.github.user32694.ledgerplatform.reconciliation;

public record ReconciliationRuleDraftCommand(
        long amountToleranceCents, int queryWindowHours, String operator) {}
