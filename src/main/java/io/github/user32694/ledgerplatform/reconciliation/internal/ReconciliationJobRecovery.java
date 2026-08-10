package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ReconciliationJobRecovery {
    private static final String ABANDONED_MESSAGE = "Application restarted before run completion";

    private final ReconciliationStore store;
    private final ReconciliationJobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final Instant recoveryCutoff;

    ReconciliationJobRecovery(
            ReconciliationStore store,
            ReconciliationJobLauncher jobLauncher,
            JobExplorer jobExplorer,
            JobRepository jobRepository,
            JobOperator jobOperator) {
        this.store = store;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.recoveryCutoff = toDatabasePrecision(Instant.now());
    }

    static Instant toDatabasePrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        for (var run : store.findRecoverableRuns(recoveryCutoff)) {
            if (run.status() == io.github.user32694.ledgerplatform.reconciliation.RunStatus.QUEUED
                    && run.batchJobExecutionId() == null) {
                jobLauncher.submit(run.id());
            } else if (run.status() == io.github.user32694.ledgerplatform.reconciliation.RunStatus.RUNNING
                    && run.batchJobExecutionId() != null
                    && run.restartCount() == 0) {
                restartAbandonedExecution(run.id(), run.batchJobExecutionId());
            }
        }
    }

    private void restartAbandonedExecution(java.util.UUID runId, long executionId) {
        var execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            store.failRun(runId, ABANDONED_MESSAGE);
            return;
        }
        execution.setStatus(BatchStatus.FAILED);
        execution.setEndTime(java.time.LocalDateTime.now());
        execution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", ABANDONED_MESSAGE));
        jobRepository.update(execution);
        store.failRun(runId, ABANDONED_MESSAGE);
        try {
            jobOperator.restart(executionId);
        } catch (Exception exception) {
            // The failed run remains available for an explicit operator restart.
        }
    }
}
