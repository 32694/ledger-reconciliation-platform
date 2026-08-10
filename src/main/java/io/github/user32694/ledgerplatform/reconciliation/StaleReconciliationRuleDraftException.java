package io.github.user32694.ledgerplatform.reconciliation;

public final class StaleReconciliationRuleDraftException extends IllegalStateException {
    public StaleReconciliationRuleDraftException() {
        super("Reconciliation rule draft snapshot is stale");
    }
}
