package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.core.task.TaskRejectedException;

class ReconciliationJobLauncherTest {
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
