package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Sql(statements = {
        "DELETE FROM audit.audit_event",
        "TRUNCATE reconciliation.reconciliation_case_event",
        "DELETE FROM reconciliation.reconciliation_resolution",
        "DELETE FROM reconciliation.reconciliation_result",
        "DELETE FROM reconciliation.reconciliation_run",
        "DELETE FROM reconciliation.channel_statement_entry",
        "DELETE FROM reconciliation.reconciliation_batch",
        "DELETE FROM payments.payment_instruction",
        "DELETE FROM accounts.customer_account",
        "DELETE FROM ledger.ledger_entry",
        "DELETE FROM ledger.ledger_transaction",
        "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
        "DELETE FROM audit.audit_event",
        "TRUNCATE reconciliation.reconciliation_case_event",
        "DELETE FROM reconciliation.reconciliation_resolution",
        "DELETE FROM reconciliation.reconciliation_result",
        "DELETE FROM reconciliation.reconciliation_run",
        "DELETE FROM reconciliation.channel_statement_entry",
        "DELETE FROM reconciliation.reconciliation_batch",
        "DELETE FROM payments.payment_instruction",
        "DELETE FROM accounts.customer_account",
        "DELETE FROM ledger.ledger_entry",
        "DELETE FROM ledger.ledger_transaction",
        "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationTaskDispatcherTest {
    @Autowired ReconciliationApi reconciliationApi;
    @Autowired ReconciliationStore store;
    @Autowired ReconciliationRunner runner;
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void rejectedTaskFailsThePersistedRunAndAuditsTheRequester() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "rejected.csv", csv("CH-REJECTED,1,2026-01-15T09:30:00Z\n"), "admin"));
        var queued = store.queueRun(batch.id(), "operator-rejected").run();
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("test rejection");
        };

        new ReconciliationTaskDispatcher(rejectingExecutor, runner, store).submit(queued.id());

        assertThat(reconciliationApi.findRuns(batch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.errorMessage()).contains("TaskRejectedException");
                });
        assertThat(reconciliationApi.getBatch(batch.id()).status())
                .isEqualTo(BatchStatus.RECONCILIATION_FAILED);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM reconciliation.reconciliation_run
                WHERE batch_id = ? AND status IN ('QUEUED', 'RUNNING')
                """,
                Integer.class,
                batch.id())).isZero();
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, AuditOutcome.FAILED, 100))
                .singleElement()
                .satisfies(event -> assertThat(event.actor()).isEqualTo("operator-rejected"));
    }

    @Test
    void runnerFailsQueuedRunWhenBatchCannotEnterRunningState() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "start-conflict.csv", csv("CH-START-CONFLICT,1,2026-01-15T09:30:00Z\n"), "admin"));
        var queued = store.queueRun(batch.id(), "operator-conflict").run();
        jdbcTemplate.update(
                "UPDATE reconciliation.reconciliation_batch SET status = 'RUNNING', started_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-01-15T10:00:00Z")),
                batch.id());

        runner.execute(queued.id());

        assertThat(reconciliationApi.findRuns(batch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.errorMessage()).contains("IllegalStateException");
                });
        assertThat(reconciliationApi.getBatch(batch.id()).status())
                .isEqualTo(BatchStatus.RECONCILIATION_FAILED);
    }

    @Test
    void runnerLogsWhenFailureStateCannotBePersisted(CapturedOutput output) {
        var runId = UUID.randomUUID();
        var failingStore = mock(ReconciliationStore.class);
        when(failingStore.markRunRunning(runId)).thenThrow(new IllegalStateException("start failed"));
        doThrow(new IllegalStateException("persistence failed"))
                .when(failingStore).failRun(org.mockito.ArgumentMatchers.eq(runId), anyString());
        var failingRunner = new ReconciliationRunner(
                failingStore, mock(PaymentsApi.class), mock(ReconciliationMatcher.class));

        failingRunner.execute(runId);

        assertThat(output)
                .contains("Failed to persist reconciliation run failure")
                .contains(runId.toString())
                .contains("persistence failed");
    }

    @Test
    void executorFactoryLeavesInitializationToSpring() {
        var executor = (ThreadPoolTaskExecutor)
                new ReconciliationExecutionConfig().reconciliationTaskExecutor();
        try {
            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not initialized");
        } finally {
            executor.shutdown();
        }
    }

    private static byte[] csv(String rows) {
        return ("channel_transaction_id,amount_cents,occurred_at\n" + rows)
                .getBytes(StandardCharsets.UTF_8);
    }
}
