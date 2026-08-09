package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ReconciliationRunRecovery {
    private final ReconciliationStore store;
    private final Instant recoveryCutoff;

    ReconciliationRunRecovery(ReconciliationStore store) {
        this.store = store;
        this.recoveryCutoff = toDatabasePrecision(Instant.now());
    }

    static Instant toDatabasePrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        store.recoverAbandonedRuns(
                "Application restarted before run completion", recoveryCutoff);
    }
}
