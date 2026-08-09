package io.github.user32694.ledgerplatform.reconciliation.internal;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ReconciliationRunRecovery {
    private final ReconciliationStore store;

    ReconciliationRunRecovery(ReconciliationStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        store.recoverAbandonedRuns("Application restarted before run completion");
    }
}
