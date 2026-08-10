package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.batch.core.JobInstance;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.core.task.TaskRejectedException;

class ReconciliationJobLauncherTest {
    @Test
    void usesAStableRecoverableMessageForStoppedExecutions() {
        var execution = new JobExecution(1L, new org.springframework.batch.core.JobParameters());
        execution.setStatus(BatchStatus.STOPPED);

        assertThat(ReconciliationJobExecutionListener.stableFailureMessage(execution))
                .isEqualTo("JobExecution stopped");
    }
    @Test
    void launchesWithRunIdAsTheOnlyIdentifyingParameter() throws Exception {
        UUID runId = UUID.randomUUID();
        Job job = job();
        AtomicReference<JobParameters> captured = new AtomicReference<>();
        JobLauncher batchLauncher = (submittedJob, parameters) -> {
            assertThat(submittedJob).isSameAs(job);
            captured.set(parameters);
            return null;
        };

        new ReconciliationJobLauncher(job, batchLauncher, (id, message) -> {}).submit(runId);

        assertThat(captured.get().getParameters()).containsOnlyKeys("runId");
        var runIdParameter = captured.get().getParameters().get("runId");
        assertThat(runIdParameter.getValue()).isEqualTo(runId.toString());
        assertThat(runIdParameter.isIdentifying()).isTrue();
    }

    @Test
    void persistsStableFailureWhenBatchLaunchIsRejected() throws Exception {
        UUID runId = UUID.randomUUID();
        JobLauncher batchLauncher = (job, parameters) -> {
            throw new TaskRejectedException("queue full");
        };
        AtomicReference<String> failure = new AtomicReference<>();

        new ReconciliationJobLauncher(job(), batchLauncher, (id, message) -> {
            assertThat(id).isEqualTo(runId);
            failure.set(message);
        }).submit(runId);

        assertThat(failure.get()).isEqualTo("TaskRejectedException: reconciliation batch launcher rejected the task");
    }

    @Test
    void recordsTheExecutionReturnedBeforeTheJobListenerRuns() {
        UUID runId = UUID.randomUUID();
        var execution = new JobExecution(
                new JobInstance(41L, "reconciliationJob"),
                51L,
                ReconciliationJobLauncher.parameters(runId));
        JobLauncher batchLauncher = (job, parameters) -> execution;
        AtomicReference<List<Long>> recorded = new AtomicReference<>();

        new ReconciliationJobLauncher(
                        job(),
                        batchLauncher,
                        (id, instanceId, executionId) -> {
                            assertThat(id).isEqualTo(runId);
                            recorded.set(List.of(instanceId, executionId));
                        },
                        (id, message) -> {})
                .submit(runId);

        assertThat(recorded.get()).containsExactly(41L, 51L);
    }

    private static Job job() {
        return new Job() {
            @Override
            public String getName() {
                return "reconciliationJob";
            }

            @Override
            public void execute(JobExecution execution) {}
        };
    }
}
