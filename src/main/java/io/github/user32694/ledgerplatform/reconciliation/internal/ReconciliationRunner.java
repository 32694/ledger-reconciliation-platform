package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class ReconciliationRunner {
    private final ReconciliationStore store;
    private final PaymentsApi paymentsApi;
    private final ReconciliationMatcher matcher;

    ReconciliationRunner(
            ReconciliationStore store, PaymentsApi paymentsApi, ReconciliationMatcher matcher) {
        this.store = store;
        this.paymentsApi = paymentsApi;
        this.matcher = matcher;
    }

    ReconciliationBatchView run(UUID batchId) {
        var started = store.markRunningOrReturnCompleted(batchId);
        if (started.status() == BatchStatus.COMPLETED) {
            return started;
        }
        try {
            var entries = store.findStatementEntries(batchId);
            var payments = paymentsApi.findSucceededTopUps(started.periodStart(), started.periodEnd());
            var drafts = matcher.match(entries, payments);
            return store.replaceResultsAndComplete(batchId, drafts);
        } catch (RuntimeException exception) {
            store.markReconciliationFailed(batchId, stableMessage(exception));
            throw exception;
        }
    }

    private static String stableMessage(RuntimeException exception) {
        String detail = exception.getMessage() == null ? "" : ": " + exception.getMessage();
        String message = exception.getClass().getSimpleName() + detail;
        return message.substring(0, Math.min(message.length(), 2000));
    }
}
