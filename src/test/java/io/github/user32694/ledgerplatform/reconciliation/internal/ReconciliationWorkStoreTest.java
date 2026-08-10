package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DELETE FROM audit.audit_event",
        "TRUNCATE reconciliation.reconciliation_case_event",
        "DELETE FROM reconciliation.reconciliation_resolution",
        "DELETE FROM reconciliation.reconciliation_result",
        "DELETE FROM reconciliation.reconciliation_result_work",
        "DELETE FROM reconciliation.reconciliation_run",
        "DELETE FROM reconciliation.channel_statement_entry",
        "DELETE FROM reconciliation.reconciliation_batch"
})
class ReconciliationWorkStoreTest {
    private static final UUID BATCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_STATEMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_STATEMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID PAYMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Autowired ReconciliationStore store;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AuditApi auditApi;

    @Test
    void atomicallyClaimsAQueuedRunForRecoveryOnce() throws Exception {
        insertBatch("IMPORTED", 1);
        var queued = store.queueRun(BATCH_ID, "operator").run();

        assertThat(race(() -> store.claimQueuedRecovery(queued.id())))
                .containsExactlyInAnyOrder(true, false);
        assertThat(store.getRun(queued.id()).status())
                .isEqualTo(io.github.user32694.ledgerplatform.reconciliation.RunStatus.RUNNING);
    }

    @Test
    void atomicallyClaimsAStaleRunningExecutionOnceAndCanClearItsMissingExecutionId() throws Exception {
        insertBatch("RUNNING", 1);
        insertRun(SECOND_RUN_ID, 2, "RUNNING");
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_run
                SET batch_job_execution_id = 123, batch_job_instance_id = 12
                WHERE id = ?
                """, SECOND_RUN_ID);

        assertThat(race(() -> store.claimStaleRunningRecovery(
                        SECOND_RUN_ID, 123L, 0, true, "missing execution")))
                .containsExactlyInAnyOrder(true, false);
        assertThat(store.getRun(SECOND_RUN_ID))
                .satisfies(failed -> {
                    assertThat(failed.status())
                            .isEqualTo(io.github.user32694.ledgerplatform.reconciliation.RunStatus.FAILED);
                    assertThat(failed.batchJobExecutionId()).isNull();
                    assertThat(failed.errorMessage()).isEqualTo("missing execution");
                });
    }

    @Test
    void keepsAnAlreadyRunningRunAndBatchStartTimeUnchanged() {
        var startedAt = Instant.parse("2026-08-10T01:02:03Z");
        insertBatch("RUNNING", 2);
        insertRun(SECOND_RUN_ID, 2, "RUNNING");
        jdbcTemplate.update(
                "UPDATE reconciliation.reconciliation_batch SET started_at = ? WHERE id = ?",
                Timestamp.from(startedAt), BATCH_ID);
        jdbcTemplate.update(
                "UPDATE reconciliation.reconciliation_run SET started_at = ? WHERE id = ?",
                Timestamp.from(startedAt), SECOND_RUN_ID);

        var first = store.markRunRunning(SECOND_RUN_ID);
        var repeated = store.markRunRunning(SECOND_RUN_ID);

        assertThat(first.status()).isEqualTo(io.github.user32694.ledgerplatform.reconciliation.RunStatus.RUNNING);
        assertThat(repeated.startedAt()).isEqualTo(startedAt);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT started_at FROM reconciliation.reconciliation_batch WHERE id = ?",
                        Timestamp.class, BATCH_ID).toInstant())
                .isEqualTo(startedAt);
    }

    @Test
    void rejectsAnAlreadyRunningRunWhenItsBatchIsNotRunning() {
        insertBatch("IMPORTED", 2);
        insertRun(SECOND_RUN_ID, 2, "RUNNING");

        assertThatThrownBy(() -> store.markRunRunning(SECOND_RUN_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Running run requires a RUNNING batch, but was IMPORTED");
    }

    @Test
    void stillRejectsStartingRunsInOtherStates() {
        insertBatch("RUNNING", 2);
        insertRun(FIRST_RUN_ID, 1, "FAILED");

        assertThatThrownBy(() -> store.markRunRunning(FIRST_RUN_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run cannot start from FAILED");
    }

    @Test
    void writesWorkIdempotentlyPerRunWithoutPublishingCanonicalResults() {
        insertBatch("RUNNING", 2);
        insertStatement(FIRST_STATEMENT_ID, 2, "CHANNEL-1");
        insertRun(FIRST_RUN_ID, 1, "FAILED");
        insertRun(SECOND_RUN_ID, 2, "RUNNING");
        var result = new ReconciliationWorkResult(
                FIRST_STATEMENT_ID,
                PAYMENT_ID,
                ResultType.AMOUNT_MISMATCH,
                ResolutionStatus.OPEN);

        store.writeWorkResults(FIRST_RUN_ID, BATCH_ID, List.of(result, result));
        store.writeWorkResults(SECOND_RUN_ID, BATCH_ID, List.of(result));

        assertThat(count("reconciliation.reconciliation_result_work")).isEqualTo(2);
        assertThat(store.findResults(BATCH_ID)).isEmpty();
    }

    @Test
    void promotesOnlyTheSelectedRunAndClearsAllBatchWork() {
        insertBatch("RUNNING", 2);
        insertStatement(FIRST_STATEMENT_ID, 2, "CHANNEL-1");
        insertStatement(SECOND_STATEMENT_ID, 3, "CHANNEL-2");
        insertRun(FIRST_RUN_ID, 1, "FAILED");
        insertRun(SECOND_RUN_ID, 2, "RUNNING");
        UUID staleCanonicalId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, payment_id, result_type,
                     resolution_status, created_at)
                VALUES (?, ?, ?, NULL, 'CHANNEL_ONLY', 'OPEN', CURRENT_TIMESTAMP)
                """, staleCanonicalId, BATCH_ID, SECOND_STATEMENT_ID);
        store.writeWorkResults(FIRST_RUN_ID, BATCH_ID, List.of(new ReconciliationWorkResult(
                SECOND_STATEMENT_ID, null, ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN)));
        store.writeWorkResults(SECOND_RUN_ID, BATCH_ID, List.of(new ReconciliationWorkResult(
                FIRST_STATEMENT_ID, PAYMENT_ID, ResultType.MATCHED, ResolutionStatus.NOT_REQUIRED)));
        UUID selectedWorkId = jdbcTemplate.queryForObject("""
                SELECT id FROM reconciliation.reconciliation_result_work WHERE run_id = ?
                """, UUID.class, SECOND_RUN_ID);

        store.promoteWorkResults(SECOND_RUN_ID);

        assertThat(jdbcTemplate.queryForList("""
                SELECT id FROM reconciliation.reconciliation_result WHERE batch_id = ?
                """, UUID.class, BATCH_ID)).containsExactly(selectedWorkId);
        assertThat(count("reconciliation.reconciliation_result_work")).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, matched_rows, difference_rows
                FROM reconciliation.reconciliation_batch WHERE id = ?
                """, BATCH_ID))
                .containsEntry("status", "COMPLETED")
                .containsEntry("matched_rows", 1)
                .containsEntry("difference_rows", 0);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, matched_rows, difference_rows
                FROM reconciliation.reconciliation_run WHERE id = ?
                """, SECOND_RUN_ID))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("matched_rows", 1)
                .containsEntry("difference_rows", 0);
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_RUN, AuditOutcome.SUCCEEDED, 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("operator-2");
                    assertThat(event.aggregateId()).isEqualTo(BATCH_ID.toString());
                    assertThat(event.correlationReference()).isEqualTo(SECOND_RUN_ID.toString());
                });
    }

    private void insertBatch(String status, int totalRows) {
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_batch
                    (id, source_type, file_name, file_sha256, period_start, period_end,
                     status, total_rows, created_by, created_at, started_at)
                VALUES (?, 'SYNTHETIC_CHANNEL', 'work.csv', ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, ?, 'importer', CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'RUNNING' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, BATCH_ID, "a".repeat(64), status, totalRows, status);
    }

    private void insertStatement(UUID id, int lineNumber, String reference) {
        jdbcTemplate.update("""
                INSERT INTO reconciliation.channel_statement_entry
                    (id, batch_id, line_number, channel_transaction_id, amount_cents, occurred_at)
                VALUES (?, ?, ?, ?, 100, CURRENT_TIMESTAMP)
                """, id, BATCH_ID, lineNumber, reference);
    }

    private void insertRun(UUID id, int attemptNumber, String status) {
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at,
                     started_at, completed_at, error_message)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'FAILED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? = 'FAILED' THEN 'previous failure' ELSE NULL END)
                """, id, BATCH_ID, attemptNumber, status, "operator-" + attemptNumber, status, status);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static List<Boolean> race(Callable<Boolean> action) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> contender = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return action.call();
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(contender);
            var second = executor.submit(contender);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
