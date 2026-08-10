package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class ReconciliationJobLauncher {
    @FunctionalInterface
    interface ExecutionRecorder {
        void accept(UUID runId, Long jobInstanceId, Long jobExecutionId);
    }

    private final Job reconciliationJob;
    private final JobLauncher batchJobLauncher;
    private final ExecutionRecorder executionRecorder;
    private final BiConsumer<UUID, String> failureRecorder;

    @Autowired
    ReconciliationJobLauncher(
            Job reconciliationJob,
            @Qualifier("reconciliationBatchJobLauncher") JobLauncher batchJobLauncher,
            ReconciliationStore store) {
        this(reconciliationJob, batchJobLauncher, store::recordSubmittedExecution, store::failRun);
    }

    ReconciliationJobLauncher(
            Job reconciliationJob, JobLauncher batchJobLauncher, BiConsumer<UUID, String> failureRecorder) {
        this(reconciliationJob, batchJobLauncher, (runId, instanceId, executionId) -> {}, failureRecorder);
    }

    ReconciliationJobLauncher(
            Job reconciliationJob,
            JobLauncher batchJobLauncher,
            ExecutionRecorder executionRecorder,
            BiConsumer<UUID, String> failureRecorder) {
        this.reconciliationJob = reconciliationJob;
        this.batchJobLauncher = batchJobLauncher;
        this.executionRecorder = executionRecorder;
        this.failureRecorder = failureRecorder;
    }

    boolean submit(UUID runId) {
        try {
            var execution = batchJobLauncher.run(reconciliationJob, parameters(runId));
            if (execution != null && execution.getJobInstance() != null && execution.getId() != null) {
                executionRecorder.accept(
                        runId, execution.getJobInstance().getInstanceId(), execution.getId());
            }
            return true;
        } catch (TaskRejectedException exception) {
            failureRecorder.accept(runId, "TaskRejectedException: reconciliation batch launcher rejected the task");
            return false;
        } catch (Exception exception) {
            failureRecorder.accept(runId, stableMessage(exception));
            return false;
        }
    }

    String jobName() {
        return reconciliationJob.getName();
    }

    static org.springframework.batch.core.JobParameters parameters(UUID runId) {
        return new JobParametersBuilder()
                .addString("runId", runId.toString(), true)
                .toJobParameters();
    }

    private static String stableMessage(Exception exception) {
        String detail = exception.getMessage() == null ? "" : ": " + exception.getMessage();
        String message = exception.getClass().getSimpleName() + detail;
        return message.substring(0, Math.min(message.length(), 2000));
    }
}
