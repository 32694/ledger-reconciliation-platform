package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

@Component
class ReconciliationTaskDispatcher {
    private final TaskExecutor taskExecutor;
    private final ReconciliationRunner runner;
    private final ReconciliationStore store;

    ReconciliationTaskDispatcher(
            @Qualifier("reconciliationTaskExecutor") TaskExecutor taskExecutor,
            ReconciliationRunner runner,
            ReconciliationStore store) {
        this.taskExecutor = taskExecutor;
        this.runner = runner;
        this.store = store;
    }

    void submit(UUID runId) {
        try {
            taskExecutor.execute(() -> runner.execute(runId));
        } catch (TaskRejectedException exception) {
            store.failRun(runId, "TaskRejectedException: reconciliation executor rejected the task");
        }
    }
}
