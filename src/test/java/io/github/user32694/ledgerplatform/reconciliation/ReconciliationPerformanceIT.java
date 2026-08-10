package io.github.user32694.ledgerplatform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
class ReconciliationPerformanceIT {
    private static final int ROW_COUNT = 100_000;
    private static final int EXPECTED_RESULT_COUNT = 110_000;
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired ReconciliationApi reconciliationApi;
    @Autowired AccountsApi accountsApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void cleanBefore() {
        dropPerformanceTriggers();
        cleanDatabase();
    }

    @AfterEach
    void cleanAfter() {
        dropPerformanceTriggers();
        cleanDatabase();
    }

    @Test
    void processesOneHundredThousandRowsWithOneRestartAfterCommittedChunks() throws Exception {
        long startedNanos = System.nanoTime();
        var account = accountsApi.create("Performance Fixture");
        insertPayments(account.id());
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "reconciliation-performance.csv", generatedCsvFixture(), "performance"));

        installFailureAfterTwoCommittedChunks();
        var submitted = reconciliationApi.startRun(batch.id(), "performance");
        var progress = new ArrayList<Integer>();
        var failed = awaitRun(batch.id(), RunStatus.FAILED, progress);

        assertThat(failed.id()).isEqualTo(submitted.id());
        assertThat(failed.processedItems()).isGreaterThanOrEqualTo(1_000);
        assertThat(progress).isSorted();
        assertThat(workRowsForRun(failed.id())).isEqualTo(1_000);
        assertThat(duplicateWorkStatementRows(failed.id())).isZero();
        assertThat(duplicateWorkPaymentRows(failed.id())).isZero();

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_performance_failure ON reconciliation.reconciliation_result_work");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reconciliation.fail_performance_after_two_chunks()");
        installReplayGuard();

        reconciliationApi.restartRun(failed.id(), "performance");
        var completed = awaitRun(batch.id(), RunStatus.SUCCEEDED, progress);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        long channelRowsPerSecond = elapsedMillis == 0 ? ROW_COUNT : ROW_COUNT * 1_000 / elapsedMillis;

        assertThat(completed.id()).isEqualTo(failed.id());
        assertThat(completed.restartCount()).isEqualTo(1);
        assertThat(completed.processedItems()).isEqualTo(completed.totalItems());
        assertThat(progress).isSorted();
        assertThat(resultCount("MATCHED")).isEqualTo(10_000);
        assertThat(resultCount("AMOUNT_MISMATCH")).isEqualTo(10_000);
        assertThat(resultCount("CHANNEL_ONLY")).isEqualTo(80_000);
        assertThat(resultCount("INTERNAL_ONLY")).isEqualTo(10_000);
        assertThat(totalResults()).isEqualTo(EXPECTED_RESULT_COUNT);
        assertThat(duplicateResultStatementRows()).isZero();
        assertThat(duplicateResultPaymentRows()).isZero();
        assertThat(workRowsForRun(completed.id())).isZero();

        System.out.printf(
                "reconciliation-performance elapsedMs=%d channelRowsPerSecond=%d channelRows=%d resultRows=%d restartCount=%d%n",
                elapsedMillis, channelRowsPerSecond, ROW_COUNT, EXPECTED_RESULT_COUNT, completed.restartCount());
    }

    private void insertPayments(UUID payeeAccountId) {
        jdbcTemplate.update("""
                INSERT INTO payments.payment_instruction
                    (id, idempotency_key, request_fingerprint, channel_reference, payment_type,
                     payer_account_id, payee_account_id, amount_cents, currency, status,
                     failure_reason, version, created_at, completed_at,
                     original_payment_id, operation_reason)
                SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(row), 12, '0'))::uuid,
                       'performance-match-' || row, repeat('0', 64),
                       'PERF-MATCH-' || lpad(row::text, 6, '0'), 'TOP_UP', NULL::uuid, ?, 1000,
                       'CNY', 'SUCCEEDED', NULL::varchar, 0, ?::timestamptz, ?::timestamptz,
                       NULL::uuid, NULL::varchar
                FROM generate_series(10, 100000, 10) AS row
                UNION ALL
                SELECT ('00000000-0000-0000-0002-' || lpad(to_hex(row), 12, '0'))::uuid,
                       'performance-internal-' || row, repeat('0', 64),
                       'PERF-INTERNAL-' || lpad(row::text, 6, '0'), 'TOP_UP', NULL::uuid, ?, 2000,
                       'CNY', 'SUCCEEDED', NULL::varchar, 0, ?::timestamptz, ?::timestamptz,
                       NULL::uuid, NULL::varchar
                FROM generate_series(10, 100000, 10) AS row
                UNION ALL
                SELECT ('00000000-0000-0000-0003-' || lpad(to_hex(row), 12, '0'))::uuid,
                       'performance-mismatch-' || row, repeat('0', 64),
                       'PERF-MISMATCH-' || lpad(row::text, 6, '0'), 'TOP_UP', NULL::uuid, ?, 1000,
                       'CNY', 'SUCCEEDED', NULL::varchar, 0, ?::timestamptz, ?::timestamptz,
                       NULL::uuid, NULL::varchar
                FROM generate_series(1, 100000, 10) AS row
                """,
                payeeAccountId, Timestamp.from(OCCURRED_AT), Timestamp.from(OCCURRED_AT),
                payeeAccountId, Timestamp.from(OCCURRED_AT), Timestamp.from(OCCURRED_AT),
                payeeAccountId, Timestamp.from(OCCURRED_AT), Timestamp.from(OCCURRED_AT));
    }

    private void installFailureAfterTwoCommittedChunks() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reconciliation.fail_performance_after_two_chunks()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                DECLARE line_number_value INTEGER;
                BEGIN
                    SELECT line_number INTO line_number_value
                    FROM reconciliation.channel_statement_entry
                    WHERE id = NEW.statement_entry_id;
                    IF line_number_value >= 1002 THEN
                        RAISE EXCEPTION 'deterministic performance failure after two committed chunks';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_performance_failure
                BEFORE INSERT ON reconciliation.reconciliation_result_work
                FOR EACH ROW EXECUTE FUNCTION reconciliation.fail_performance_after_two_chunks()
                """);
    }

    private void installReplayGuard() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reconciliation.reject_performance_replay()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                DECLARE line_number_value INTEGER;
                BEGIN
                    IF NEW.statement_entry_id IS NULL THEN
                        RETURN NEW;
                    END IF;
                    SELECT line_number INTO line_number_value
                    FROM reconciliation.channel_statement_entry
                    WHERE id = NEW.statement_entry_id;
                    IF line_number_value <= 1001 THEN
                        RAISE EXCEPTION 'checkpoint replay detected';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_performance_replay_guard
                BEFORE INSERT ON reconciliation.reconciliation_result_work
                FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_performance_replay()
                """);
    }

    private void dropPerformanceTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_performance_failure ON reconciliation.reconciliation_result_work");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reconciliation.fail_performance_after_two_chunks()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_performance_replay_guard ON reconciliation.reconciliation_result_work");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reconciliation.reject_performance_replay()");
    }

    private ReconciliationRunView awaitRun(
            UUID batchId, RunStatus expected, List<Integer> progress) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        ReconciliationRunView latest = null;
        while (System.nanoTime() < deadline) {
            latest = reconciliationApi.findRuns(batchId).get(0);
            progress.add(latest.processedItems());
            if (latest.status() == expected) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for reconciliation run; latest=" + latest);
    }

    private byte[] generatedCsvFixture() throws IOException, InterruptedException {
        Path output = temporaryDirectory.resolve("reconciliation-performance.csv");
        var process = new ProcessBuilder(
                "scripts/generate-reconciliation-demo.sh", output.toString(), Integer.toString(ROW_COUNT))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        assertThat(process.waitFor()).isZero();
        return Files.readAllBytes(output);
    }

    private int workRowsForRun(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_result_work WHERE run_id = ?",
                Integer.class, runId);
    }

    private int resultCount(String resultType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_result WHERE result_type = ?",
                Integer.class, resultType);
    }

    private int totalResults() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_result", Integer.class);
    }

    private int duplicateWorkStatementRows(UUID runId) {
        return duplicateCount("reconciliation.reconciliation_result_work", "run_id = ?", runId, "statement_entry_id");
    }

    private int duplicateWorkPaymentRows(UUID runId) {
        return duplicateCount("reconciliation.reconciliation_result_work", "run_id = ?", runId, "payment_id");
    }

    private int duplicateResultStatementRows() {
        return duplicateCount("reconciliation.reconciliation_result", "true", null, "statement_entry_id");
    }

    private int duplicateResultPaymentRows() {
        return duplicateCount("reconciliation.reconciliation_result", "true", null, "payment_id");
    }

    private int duplicateCount(String table, String condition, UUID runId, String column) {
        String sql = "SELECT count(*) FROM (SELECT " + column + " FROM " + table
                + " WHERE " + condition + " AND " + column + " IS NOT NULL GROUP BY " + column
                + " HAVING count(*) > 1) duplicates";
        return runId == null
                ? jdbcTemplate.queryForObject(sql, Integer.class)
                : jdbcTemplate.queryForObject(sql, Integer.class, runId);
    }

    private void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE batch.batch_job_instance CASCADE");
        jdbcTemplate.execute("DELETE FROM audit.audit_event");
        jdbcTemplate.execute("TRUNCATE reconciliation.reconciliation_case_event");
        jdbcTemplate.execute("DELETE FROM reconciliation.reconciliation_resolution");
        jdbcTemplate.execute("DELETE FROM reconciliation.reconciliation_result");
        jdbcTemplate.execute("DELETE FROM reconciliation.reconciliation_result_work");
        jdbcTemplate.execute("DELETE FROM reconciliation.reconciliation_run");
        jdbcTemplate.execute("DELETE FROM reconciliation.channel_statement_entry");
        jdbcTemplate.execute("DELETE FROM reconciliation.reconciliation_batch");
        jdbcTemplate.execute("DELETE FROM payments.payment_instruction");
        jdbcTemplate.execute("DELETE FROM accounts.customer_account");
        jdbcTemplate.execute("DELETE FROM ledger.ledger_entry");
        jdbcTemplate.execute("DELETE FROM ledger.ledger_transaction");
        jdbcTemplate.execute("DELETE FROM ledger.ledger_account");
    }
}
