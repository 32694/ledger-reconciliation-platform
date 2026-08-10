package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationRunRecoveryTest {
    @Test
    void truncatesRecoveryCutoffToPostgresMicrosecondPrecision() {
        assertThat(ReconciliationJobRecovery.toDatabasePrecision(
                        Instant.parse("2026-01-15T10:00:00.123456789Z")))
                .isEqualTo(Instant.parse("2026-01-15T10:00:00.123456Z"));
    }

    @Test
    void restartsTheSameRunAttemptAndJobInstanceExactlyOnce() {
        UUID batchId = UUID.randomUUID();
        var run = ReconciliationRunEntity.queued(
                batchId, 3, "operator", Instant.parse("2026-01-15T10:00:00Z"));
        UUID runId = run.id();

        run.start(41L, 51L, Instant.parse("2026-01-15T10:01:00Z"));
        run.fail("interrupted", Instant.parse("2026-01-15T10:02:00Z"));
        run.start(41L, 52L, Instant.parse("2026-01-15T10:03:00Z"));

        var restarted = run.toView();
        assertThat(restarted.id()).isEqualTo(runId);
        assertThat(restarted.batchId()).isEqualTo(batchId);
        assertThat(restarted.attemptNumber()).isEqualTo(3);
        assertThat(restarted.batchJobInstanceId()).isEqualTo(41L);
        assertThat(restarted.batchJobExecutionId()).isEqualTo(52L);
        assertThat(restarted.restartCount()).isOne();
    }

    @Test
    void classifiesEveryStaleRunningRunForRestartOrFailure() {
        var restartable = runningRun(61L, 71L);
        var missingExecution = runningRun(62L, null);
        var alreadyRestarted = ReconciliationRunEntity.queued(
                UUID.randomUUID(), 1, "operator", Instant.parse("2026-01-15T10:00:00Z"));
        alreadyRestarted.start(63L, 73L, Instant.parse("2026-01-15T10:01:00Z"));
        alreadyRestarted.fail("first interruption", Instant.parse("2026-01-15T10:02:00Z"));
        alreadyRestarted.start(63L, 74L, Instant.parse("2026-01-15T10:03:00Z"));

        assertThat(ReconciliationJobRecovery.actionFor(restartable.toView()))
                .isEqualTo(ReconciliationJobRecovery.RecoveryAction.RESTART);
        assertThat(ReconciliationJobRecovery.actionFor(missingExecution.toView()))
                .isEqualTo(ReconciliationJobRecovery.RecoveryAction.FAIL);
        assertThat(ReconciliationJobRecovery.actionFor(alreadyRestarted.toView()))
                .isEqualTo(ReconciliationJobRecovery.RecoveryAction.FAIL);
    }

    @Test
    void keepsCommittedProgressMonotonicAndWithinTheDeclaredTotal() {
        var run = runningRun(81L, 91L);
        run.setTotalItems(1_000);

        run.updateProgress("matchStatementEntriesStep", 500);
        assertThat(run.toView().processedItems()).isEqualTo(500);

        run.updateProgress("matchStatementEntriesStep", 400);
        assertThat(run.toView().processedItems()).isEqualTo(500);

        run.updateProgress("findInternalOnlyPaymentsStep", 1_200);
        assertThat(run.toView().processedItems()).isEqualTo(1_000);
        assertThat(run.toView().processedItems()).isLessThanOrEqualTo(run.toView().totalItems());
    }

    private static ReconciliationRunEntity runningRun(Long instanceId, Long executionId) {
        var run = ReconciliationRunEntity.queued(
                UUID.randomUUID(), 1, "operator", Instant.parse("2026-01-15T10:00:00Z"));
        run.start(instanceId, executionId, Instant.parse("2026-01-15T10:01:00Z"));
        return run;
    }
}
