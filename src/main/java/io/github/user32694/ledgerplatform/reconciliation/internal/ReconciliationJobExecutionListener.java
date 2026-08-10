package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.UUID;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.BatchStatus;
import org.springframework.stereotype.Component;

@Component
class ReconciliationJobExecutionListener implements JobExecutionListener {
    private final ReconciliationStore store;

    ReconciliationJobExecutionListener(ReconciliationStore store) {
        this.store = store;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        store.beginRun(runId(jobExecution), jobExecution.getJobInstance().getInstanceId(), jobExecution.getId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.FAILED) {
            store.failRunUnlessSucceeded(runId(jobExecution), stableMessage(jobExecution));
        }
    }

    private static UUID runId(JobExecution execution) {
        return UUID.fromString(execution.getJobParameters().getString("runId"));
    }

    private static String stableMessage(JobExecution execution) {
        String message = execution.getAllFailureExceptions().stream()
                .findFirst()
                .map(failure -> failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()))
                .orElse("JobExecution failed");
        return message.substring(0, Math.min(message.length(), 2000));
    }
}
