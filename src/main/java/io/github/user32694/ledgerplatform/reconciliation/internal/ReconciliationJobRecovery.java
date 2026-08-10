package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.RunStatus;

@Component
class ReconciliationJobRecovery {
    private static final String ABANDONED_MESSAGE = "Application restarted before run completion";
    private static final EnumSet<BatchStatus> ACTIVE_BATCH_STATUSES = EnumSet.of(
            BatchStatus.STARTING,
            BatchStatus.STARTED,
            BatchStatus.STOPPING,
            BatchStatus.UNKNOWN);
    private static final Log LOGGER = LogFactory.getLog(ReconciliationJobRecovery.class);

    private final ReconciliationStore store;
    private final ReconciliationJobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final Instant recoveryCutoff;

    enum RecoveryAction {
        SUBMIT,
        RESTART,
        FAIL
    }

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

    static RecoveryAction actionFor(ReconciliationRunView run) {
        if (run.status() == RunStatus.QUEUED && run.batchJobExecutionId() == null) {
            return RecoveryAction.SUBMIT;
        }
        if (run.status() == RunStatus.RUNNING
                && run.batchJobExecutionId() != null
                && run.restartCount() == 0) {
            return RecoveryAction.RESTART;
        }
        return RecoveryAction.FAIL;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        for (var run : store.findRecoverableRuns(recoveryCutoff)) {
            try {
                recover(run);
            } catch (Exception exception) {
                persistRecoveryFailure(run, exception);
            }
        }
        for (var run : store.findFailedRunsWithExecution(recoveryCutoff)) {
            try {
                recoverFailedExecution(run);
            } catch (Exception exception) {
                persistRecoveryFailure(run, exception);
            }
        }
    }

    private void persistRecoveryFailure(ReconciliationRunView run, Exception exception) {
        String message = stableMessage(exception);
        try {
            store.failRun(run.id(), message);
        } catch (Exception persistenceException) {
            LOGGER.error("Failed to persist recovery failure for run " + run.id(), persistenceException);
        }
    }

    private void recover(ReconciliationRunView run) throws Exception {
        if (run.status() == RunStatus.QUEUED) {
            var existingExecution = run.batchJobExecutionId() == null
                    ? findExistingExecution(run.id())
                    : jobExplorer.getJobExecution(run.batchJobExecutionId());
            if (existingExecution != null) {
                boolean claimed = store.claimQueuedRecovery(
                        run.id(),
                        existingExecution.getJobInstance().getInstanceId(),
                        existingExecution.getId());
                if (claimed) {
                    recoverStaleRunning(store.getRun(run.id()));
                }
            } else if (run.batchJobExecutionId() != null) {
                boolean claimed = store.claimQueuedRecovery(
                        run.id(), run.batchJobInstanceId(), run.batchJobExecutionId());
                if (claimed) {
                    recoverStaleRunning(store.getRun(run.id()));
                }
            } else if (store.claimQueuedRecovery(run.id(), null, null)) {
                jobLauncher.submit(run.id());
            }
            return;
        }
        if (run.status() == RunStatus.RUNNING) {
            recoverStaleRunning(run);
            return;
        }
        store.failRun(run.id(), ABANDONED_MESSAGE);
    }

    private org.springframework.batch.core.JobExecution findExistingExecution(UUID runId) {
        var jobInstance = jobExplorer.getJobInstance(
                jobLauncher.jobName(), ReconciliationJobLauncher.parameters(runId));
        return jobInstance == null ? null : jobExplorer.getLastJobExecution(jobInstance);
    }

    private void recoverFailedExecution(ReconciliationRunView run) {
        Long executionId = run.batchJobExecutionId();
        var execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            store.clearMissingFailedExecution(run.id(), executionId);
            return;
        }
        if (!ACTIVE_BATCH_STATUSES.contains(execution.getStatus())) {
            return;
        }
        execution.setStatus(BatchStatus.FAILED);
        execution.setEndTime(java.time.LocalDateTime.now());
        execution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", ABANDONED_MESSAGE));
        jobRepository.update(execution);
    }

    private void recoverStaleRunning(ReconciliationRunView run) throws Exception {
        Long executionId = run.batchJobExecutionId();
        if (executionId == null) {
            store.claimStaleRunningRecovery(
                    run.id(), null, run.restartCount(), true, ABANDONED_MESSAGE);
            return;
        }
        var execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            store.claimStaleRunningRecovery(
                    run.id(), executionId, run.restartCount(), true, ABANDONED_MESSAGE);
            return;
        }
        boolean claimed = store.claimStaleRunningRecovery(
                run.id(), executionId, run.restartCount(), false, ABANDONED_MESSAGE);
        if (!claimed) {
            return;
        }
        execution.setStatus(BatchStatus.FAILED);
        execution.setEndTime(java.time.LocalDateTime.now());
        execution.setExitStatus(new org.springframework.batch.core.ExitStatus("FAILED", ABANDONED_MESSAGE));
        jobRepository.update(execution);
        if (run.restartCount() == 0) {
            jobOperator.restart(executionId);
        }
    }

    private static String stableMessage(Exception exception) {
        String detail = exception.getMessage() == null ? "" : ": " + exception.getMessage();
        String message = exception.getClass().getSimpleName() + detail;
        return message.substring(0, Math.min(message.length(), 2000));
    }
}
