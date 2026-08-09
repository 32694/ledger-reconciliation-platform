package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ReconciliationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationRunner.class);

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

    void execute(UUID runId) {
        try {
            var run = store.markRunRunning(runId);
            var batch = store.getBatch(run.batchId());
            var entries = store.findStatementEntries(run.batchId());
            var payments = paymentsApi.findSucceededTopUps(batch.periodStart(), batch.periodEnd());
            var drafts = matcher.match(entries, payments);
            store.completeRun(runId, drafts);
        } catch (RuntimeException exception) {
            try {
                store.failRun(runId, stableMessage(exception));
            } catch (RuntimeException failureException) {
                LOGGER.error("Failed to persist reconciliation run failure for run {}", runId, failureException);
            }
        }
    }

    private static String stableMessage(RuntimeException exception) {
        String detail = exception.getMessage() == null ? "" : ": " + exception.getMessage();
        String message = exception.getClass().getSimpleName() + detail;
        return message.substring(0, Math.min(message.length(), 2000));
    }
}
