package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleDraftCommand;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRulesApi;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import java.time.Duration;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
class ReconciliationBatchJobTest {
    private static final UUID ALIPAY_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Autowired ReconciliationApi reconciliationApi;
    @Autowired ReconciliationRulesApi rulesApi;
    @Autowired AccountsApi accountsApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired ReconciliationStore store;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("jobLauncher") JobLauncher jobLauncher;
    @Autowired @Qualifier("reconciliationJob") Job reconciliationJob;
    @Autowired JobExplorer jobExplorer;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired ConfigurableApplicationContext applicationContext;

    @BeforeEach
    void cleanBefore() {
        cleanDatabase();
    }

    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    @Test
    void processesAllFourResultsWithLockedToleranceAndWindow() throws Exception {
        var rule = rulesApi.publish(
                ALIPAY_RULE_ID,
                rulesApi.saveDraft(
                        ALIPAY_RULE_ID,
                        new ReconciliationRuleDraftCommand(3, 2, "rule-editor")).id(),
                3,
                2,
                "rule-publisher");
        var account = accountsApi.create("Batch Job Customer");
        var matched = paymentsApi.topUp(new TopUpCommand("batch-matched", account.id(), 100));
        var mismatch = paymentsApi.topUp(new TopUpCommand("batch-mismatch", account.id(), 100));
        var internalOnly = paymentsApi.topUp(new TopUpCommand("batch-internal-only", account.id(), 250));
        var statementAt = matched.occurredAt().plus(1, ChronoUnit.HOURS);
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY",
                "batch-job.csv",
                csv(
                        matched.channelReference(), 102, statementAt,
                        mismatch.channelReference(), 110, statementAt.plus(1, ChronoUnit.MINUTES),
                        "batch-channel-only", 90, statementAt.plus(2, ChronoUnit.MINUTES)),
                "importer"));
        assertThat(batch.status())
                .withFailMessage("对账单导入失败: %s", batch.errorMessage())
                .isEqualTo(io.github.user32694.ledgerplatform.reconciliation.BatchStatus.IMPORTED);
        assertThat(batch.amountToleranceCents()).isEqualTo(rule.amountToleranceCents());
        assertThat(batch.queryWindowHours()).isEqualTo(rule.queryWindowHours());
        var run = store.queueRun(batch.id(), "batch-operator").run();

        JobExecution execution = jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addString("runId", run.id().toString(), true)
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName, StepExecution::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("prepareReconciliationStep", BatchStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple("matchStatementEntriesStep", BatchStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple("findInternalOnlyPaymentsStep", BatchStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple("finalizeReconciliationStep", BatchStatus.COMPLETED));
        assertThat(step(execution, "matchStatementEntriesStep").getReadCount()).isEqualTo(3);
        assertThat(step(execution, "matchStatementEntriesStep").getWriteCount()).isEqualTo(3);
        assertThat(step(execution, "findInternalOnlyPaymentsStep").getReadCount()).isEqualTo(3);
        assertThat(step(execution, "findInternalOnlyPaymentsStep").getWriteCount()).isEqualTo(1);
        assertThat(reconciliationApi.findResults(batch.id(), null, null))
                .extracting(result -> result.resultType())
                .containsExactlyInAnyOrder(
                        ResultType.MATCHED,
                        ResultType.AMOUNT_MISMATCH,
                        ResultType.CHANNEL_ONLY,
                        ResultType.INTERNAL_ONLY);
        assertThat(reconciliationApi.getBatch(batch.id()))
                .extracting(result -> result.matchedRows(), result -> result.differenceRows())
                .containsExactly(1, 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_result_work", Integer.class)).isZero();
        assertThat(internalOnly.id()).isNotNull();
    }

    @Test
    void processesTheFirstStatementBeyondTheFiveHundredRowPageBoundary() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "batch-job-501.csv", channelOnlyCsv(501), "importer"));
        assertThat(batch.status())
                .withFailMessage("对账单导入失败: %s", batch.errorMessage())
                .isEqualTo(io.github.user32694.ledgerplatform.reconciliation.BatchStatus.IMPORTED);
        var run = store.queueRun(batch.id(), "batch-operator").run();

        JobExecution execution = jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addString("runId", run.id().toString(), true)
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution, "matchStatementEntriesStep").getReadCount()).isEqualTo(501);
        assertThat(step(execution, "matchStatementEntriesStep").getWriteCount()).isEqualTo(501);
        assertThat(step(execution, "matchStatementEntriesStep").getCommitCount()).isGreaterThanOrEqualTo(2);
        assertThat(reconciliationApi.findRuns(batch.id()))
                .singleElement()
                .satisfies(completed -> {
                    assertThat(completed.processedItems()).isEqualTo(501);
                    assertThat(completed.processedItems()).isLessThanOrEqualTo(completed.totalItems());
                });
        assertThat(reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, null))
                .hasSize(501)
                .allSatisfy(result -> assertThat(result.resultType()).isEqualTo(ResultType.CHANNEL_ONLY));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_result_work", Integer.class)).isZero();
    }

    @Test
    @Sql(statements = {
        "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT IF EXISTS ck_test_restart_failure",
        "ALTER TABLE reconciliation.reconciliation_result ADD CONSTRAINT ck_test_restart_failure CHECK (FALSE)"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    @Sql(statements = {
        "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT IF EXISTS ck_test_restart_failure"
    }, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void manuallyRestartsTheSameRunAfterTheSingleAutomaticRecoveryFails() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "batch-restart.csv", channelOnlyCsv(501), "importer"));
        var queued = store.queueRun(batch.id(), "batch-operator").run();

        JobExecution firstExecution = jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addString("runId", queued.id().toString(), true)
                .toJobParameters());
        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.FAILED);

        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_run
                SET status = 'RUNNING', requested_at = ?, completed_at = NULL, error_message = NULL
                WHERE id = ?
                """, java.sql.Timestamp.from(Instant.parse("2026-01-15T10:00:00Z")), queued.id());
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_batch
                SET status = 'RUNNING', completed_at = NULL
                WHERE id = ?
                """, batch.id());

        eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));
        var automaticallyFailed = awaitRun(batch.id(), run ->
                run.status() == io.github.user32694.ledgerplatform.reconciliation.RunStatus.FAILED
                        && run.restartCount() == 1);
        assertThat(automaticallyFailed.id()).isEqualTo(queued.id());
        assertThat(automaticallyFailed.attemptNumber()).isEqualTo(queued.attemptNumber());
        assertThat(automaticallyFailed.batchJobInstanceId()).isEqualTo(firstExecution.getJobInstance().getInstanceId());
        assertThat(jobExplorer.getJobExecution(automaticallyFailed.batchJobExecutionId()))
                .satisfies(failedExecution -> {
                    assertThat(failedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
                    assertThat(failedExecution.getEndTime()).isNotNull();
                });

        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_run
                SET status = 'RUNNING', requested_at = ?, completed_at = NULL, error_message = NULL
                WHERE id = ?
                """, java.sql.Timestamp.from(Instant.parse("2026-01-15T10:00:00Z")), queued.id());
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_batch
                SET status = 'RUNNING', completed_at = NULL
                WHERE id = ?
                """, batch.id());
        eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));
        var manuallyRequired = awaitRun(batch.id(), run ->
                run.status() == io.github.user32694.ledgerplatform.reconciliation.RunStatus.FAILED
                        && run.restartCount() == 1);
        assertThat(jobExplorer.getJobExecution(manuallyRequired.batchJobExecutionId()))
                .satisfies(failedExecution -> {
                    assertThat(failedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
                    assertThat(failedExecution.getEndTime()).isNotNull();
                });

        jdbcTemplate.execute(
                "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT ck_test_restart_failure");
        reconciliationApi.restartRun(queued.id(), "manual-operator");
        var completed = awaitRun(batch.id(), run ->
                run.status() == io.github.user32694.ledgerplatform.reconciliation.RunStatus.SUCCEEDED
                        && run.restartCount() == 2);

        assertThat(completed.id()).isEqualTo(queued.id());
        assertThat(completed.attemptNumber()).isEqualTo(queued.attemptNumber());
        assertThat(completed.batchJobInstanceId()).isEqualTo(firstExecution.getJobInstance().getInstanceId());
        assertThat(completed.processedItems()).isLessThanOrEqualTo(completed.totalItems());
    }

    private io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView awaitRun(
            UUID batchId,
            java.util.function.Predicate<io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView>
                    condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView latest = null;
        while (System.nanoTime() < deadline) {
            latest = reconciliationApi.findRuns(batchId).get(0);
            if (condition.test(latest)) {
                return latest;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for reconciliation run; latest=" + latest);
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static byte[] csv(Object... values) {
        return ("channel_transaction_id,amount_cents,occurred_at\n"
                + "%s,%s,%s\n".formatted(values[0], values[1], values[2])
                + "%s,%s,%s\n".formatted(values[3], values[4], values[5])
                + "%s,%s,%s\n".formatted(values[6], values[7], values[8]))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] channelOnlyCsv(int rowCount) {
        var csv = new StringBuilder("channel_transaction_id,amount_cents,occurred_at\n");
        var occurredAt = Instant.parse("2026-08-10T00:00:00Z");
        for (int index = 1; index <= rowCount; index++) {
            csv.append("batch-channel-only-")
                    .append(index)
                    .append(",100,")
                    .append(occurredAt.plus(index, ChronoUnit.SECONDS))
                    .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
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
        jdbcTemplate.execute(
                "TRUNCATE reconciliation.reconciliation_rule_version, reconciliation.reconciliation_rule, "
                        + "reconciliation.reconciliation_channel CASCADE");
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_channel
                    (id, code, display_name, active, created_at, version)
                VALUES
                    ('00000000-0000-0000-0000-000000000001', 'ALIPAY', '支付宝', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000002', 'WECHAT_PAY', '微信支付', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000003', 'UNION_PAY', '银联', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000004', 'LEGACY_SYNTHETIC', '历史兼容渠道', false, CURRENT_TIMESTAMP, 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_rule
                    (id, scope_type, channel_id, version)
                VALUES
                    ('00000000-0000-0000-0000-000000000101', 'DEFAULT', NULL, 0),
                    ('00000000-0000-0000-0000-000000000102', 'CHANNEL', '00000000-0000-0000-0000-000000000001', 0),
                    ('00000000-0000-0000-0000-000000000103', 'CHANNEL', '00000000-0000-0000-0000-000000000002', 0),
                    ('00000000-0000-0000-0000-000000000104', 'CHANNEL', '00000000-0000-0000-0000-000000000003', 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_rule_version
                    (id, rule_id, version_number, status, amount_tolerance_cents,
                     query_window_hours, created_by, created_at, published_by, published_at)
                VALUES
                    ('00000000-0000-0000-0000-000000000201',
                     '00000000-0000-0000-0000-000000000101', 1, 'PUBLISHED', 0,
                     0, 'system', CURRENT_TIMESTAMP, 'system', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_rule
                SET active_version_id = '00000000-0000-0000-0000-000000000201'
                WHERE id = '00000000-0000-0000-0000-000000000101'
                """);
    }
}
