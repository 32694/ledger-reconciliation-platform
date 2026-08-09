package io.github.user32694.ledgerplatform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.test.context.jdbc.SqlMergeMode.MergeMode;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
@SqlMergeMode(MergeMode.MERGE)
@Sql(statements = {
        "DELETE FROM audit.audit_event",
        "DELETE FROM reconciliation.reconciliation_case_event",
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
        "DELETE FROM reconciliation.reconciliation_case_event",
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
class ReconciliationModuleTest {
    @Autowired ReconciliationApi reconciliationApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired AccountsApi accountsApi;
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired ConfigurableApplicationContext applicationContext;

    @Test
    void queuesOneActiveRunAndListsItAsTheFirstAttempt() throws InterruptedException {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "queued.csv", csv("CH-QUEUED,1,2026-01-15T09:30:00Z\n"), "admin"));

        var first = reconciliationApi.startRun(batch.id(), "operator-1");
        var repeated = reconciliationApi.startRun(batch.id(), "operator-1");

        assertThat(first.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(first.attemptNumber()).isOne();
        assertThat(first.requestedBy()).isEqualTo("operator-1");
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(reconciliationApi.findRuns(batch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.id()).isEqualTo(first.id());
                    assertThat(run.attemptNumber()).isOne();
                    assertThat(run.requestedBy()).isEqualTo("operator-1");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_run WHERE batch_id = ?",
                Integer.class,
                batch.id())).isOne();
        awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, AuditOutcome.SUCCEEDED, 100))
                .hasSize(1);
    }

    @Test
    void completesQueuedRunAsynchronouslyAndAuditsTheRequester() throws InterruptedException {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "async.csv", csv("CH-ASYNC,1,2026-01-15T09:30:00Z\n"), "admin"));

        var queued = reconciliationApi.startRun(batch.id(), "operator-async");
        var completed = awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);

        assertThat(queued.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(completed.requestedBy()).isEqualTo("operator-async");
        assertThat(reconciliationApi.getBatch(batch.id()).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN))
                .hasSize(1);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, AuditOutcome.SUCCEEDED, 100))
                .singleElement()
                .satisfies(event -> assertThat(event.actor()).isEqualTo("operator-async"));
    }

    @Test
    void importsValidStatementAtomically() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "statement.csv", csv("CH-1,12500,2026-01-15T09:30:00Z\nCH-2,7500,2026-01-15T10:45:00Z\n"), "admin"));

        assertThat(batch.status()).isEqualTo(BatchStatus.IMPORTED);
        assertThat(batch.totalRows()).isEqualTo(2);
        assertThat(batch.periodStart()).isEqualTo(java.time.Instant.parse("2026-01-15T09:30:00Z"));
        assertThat(batch.periodEnd()).isEqualTo(java.time.Instant.parse("2026-01-15T10:45:00Z"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry WHERE batch_id = ?",
                Integer.class, batch.id())).isEqualTo(2);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_IMPORT, null, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("admin");
                    assertThat(event.aggregateType()).isEqualTo("RECONCILIATION_BATCH");
                    assertThat(event.aggregateId()).isEqualTo(batch.id().toString());
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                    assertThat(event.correlationReference()).isEqualTo(batch.fileSha256());
                });
    }

    @Test
    void returnsExistingBatchForRepeatedFileHash() {
        var content = csv("CH-IDEMPOTENT,1,2026-01-15T09:30:00Z\n");
        var first = reconciliationApi.importStatement(new StatementUpload("first.csv", content, "admin"));
        var repeated = reconciliationApi.importStatement(new StatementUpload("second.csv", content, "other"));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_batch", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry", Integer.class)).isOne();
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_IMPORT, null, 100))
                .hasSize(1);
    }

    @Test
    void retainsOnlyFailureMetadataWhenCsvIsInvalid() {
        var content = csv("CH-BAD,not-an-amount,2026-01-15T09:30:00Z\n");
        var batch = reconciliationApi.importStatement(new StatementUpload("bad.csv", content, "admin"));

        assertThat(batch.status()).isEqualTo(BatchStatus.IMPORT_FAILED);
        assertThat(batch.errorMessage()).isNotBlank();
        assertThat(batch.periodStart()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry", Integer.class)).isZero();
        assertThat(reconciliationApi.importStatement(new StatementUpload("bad-again.csv", content, "admin")).id())
                .isEqualTo(batch.id());
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_IMPORT, AuditOutcome.FAILED, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("admin");
                    assertThat(event.aggregateId()).isEqualTo(batch.id().toString());
                });
    }

    @Test
    void rejectsChannelIdAlreadyImportedByAnotherBatch() {
        reconciliationApi.importStatement(new StatementUpload(
                "first.csv", csv("CH-GLOBAL,1,2026-01-15T09:30:00Z\n"), "admin"));
        var rejected = reconciliationApi.importStatement(new StatementUpload(
                "second.csv", csv("CH-GLOBAL,2,2026-01-15T10:30:00Z\n"), "admin"));

        assertThat(rejected.status()).isEqualTo(BatchStatus.IMPORT_FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_batch", Integer.class)).isEqualTo(2);
    }

    @Test
    void computesSha256AsLowercaseHex() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "hash.csv", csv("CH-HASH,1,2026-01-15T09:30:00Z\n"), "admin"));

        assertThat(batch.fileSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void runsExactMatchingForAllFourResultTypes() {
        var account = accountsApi.create("Matching Customer");
        PaymentView matched = paymentsApi.topUp(new TopUpCommand("match-1", account.id(), 100));
        PaymentView mismatch = paymentsApi.topUp(new TopUpCommand("mismatch-1", account.id(), 200));
        PaymentView internalOnly = paymentsApi.topUp(new TopUpCommand("internal-only-1", account.id(), 300));
        String rows = String.join("\n",
                row(matched.channelReference(), 100, matched.occurredAt()),
                row(mismatch.channelReference(), 201, mismatch.occurredAt()),
                row("CHANNEL-ONLY", 400, internalOnly.occurredAt())) + "\n";

        var batch = reconciliationApi.importStatement(
                new StatementUpload("matching.csv", csv(rows), "admin"));
        var completed = reconciliationApi.run(batch.id());

        assertThat(completed.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(completed.matchedRows()).isEqualTo(1);
        assertThat(completed.differenceRows()).isEqualTo(3);
        assertThat(reconciliationApi.findResults(batch.id(), null, null))
                .extracting(ReconciliationResultView::resultType)
                .containsExactly(
                        ResultType.AMOUNT_MISMATCH,
                        ResultType.CHANNEL_ONLY,
                        ResultType.INTERNAL_ONLY,
                        ResultType.MATCHED);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, null, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("SYSTEM");
                    assertThat(event.aggregateId()).isEqualTo(batch.id().toString());
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                });
    }

    @Test
    void completedBatchRunIsIdempotent() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "rerun.csv", csv("CH-RERUN,1,2026-01-15T09:30:00Z\n"), "admin"));
        var completed = reconciliationApi.run(batch.id());
        var firstResultIds = reconciliationApi.findResults(batch.id(), null, null).stream()
                .map(ReconciliationResultView::id)
                .toList();

        var repeated = reconciliationApi.run(batch.id());

        assertThat(repeated).isEqualTo(completed);
        assertThat(reconciliationApi.findResults(batch.id(), null, null).stream()
                .map(ReconciliationResultView::id)
                .toList()).containsExactlyElementsOf(firstResultIds);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, null, 100))
                .hasSize(1);
    }

    @Test
    void synchronousRunRejectsAnActiveAsyncAttemptWithoutChangingState() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "sync-active.csv", csv("CH-SYNC-ACTIVE,1,2026-01-15T09:30:00Z\n"), "admin"));
        var runId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at)
                VALUES (?, ?, 1, 'QUEUED', 'operator-async', ?)
                """,
                runId,
                batch.id(),
                Timestamp.from(Instant.parse("2026-01-15T10:00:00Z")));

        assertThatThrownBy(() -> reconciliationApi.run(batch.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Batch has an active reconciliation run");

        assertThat(reconciliationApi.getBatch(batch.id()).status()).isEqualTo(BatchStatus.IMPORTED);
        assertThat(reconciliationApi.findRuns(batch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.id()).isEqualTo(runId);
                    assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
                });
    }

    @Test
    @Sql(statements = {
        "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT IF EXISTS ck_test_result_insert_failure",
        "ALTER TABLE reconciliation.reconciliation_result ADD CONSTRAINT ck_test_result_insert_failure CHECK (FALSE)"
    }, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
    @Sql(statements = {
        "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT IF EXISTS ck_test_result_insert_failure"
    }, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
    void auditsFailedReconciliationRun() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "failed-run.csv", csv("CH-FAILED-RUN,1,2026-01-15T09:30:00Z\n"), "admin"));

        assertThatThrownBy(() -> reconciliationApi.run(batch.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(reconciliationApi.getBatch(batch.id()).status())
                .isEqualTo(BatchStatus.RECONCILIATION_FAILED);
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_RUN, AuditOutcome.FAILED, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("SYSTEM");
                    assertThat(event.aggregateId()).isEqualTo(batch.id().toString());
                });
    }

    @Test
    @Sql(statements = {
        "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT IF EXISTS ck_test_async_result_failure",
        "ALTER TABLE reconciliation.reconciliation_result ADD CONSTRAINT ck_test_async_result_failure CHECK (FALSE)"
    }, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
    @Sql(statements = {
        "ALTER TABLE reconciliation.reconciliation_result DROP CONSTRAINT IF EXISTS ck_test_async_result_failure"
    }, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
    void failedAsyncRunCanBeRetriedWithoutLosingAttemptHistory() throws InterruptedException {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "failed-async.csv", csv("CH-FAILED-ASYNC,1,2026-01-15T09:30:00Z\n"), "admin"));

        reconciliationApi.startRun(batch.id(), "operator-first");
        var first = awaitRunStatus(batch.id(), RunStatus.FAILED);
        var retry = reconciliationApi.startRun(batch.id(), "operator-retry");
        var second = awaitRunStatus(batch.id(), RunStatus.FAILED);

        assertThat(first.attemptNumber()).isOne();
        assertThat(retry.attemptNumber()).isEqualTo(2);
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(reconciliationApi.findRuns(batch.id()))
                .extracting(ReconciliationRunView::attemptNumber)
                .containsExactly(2, 1);
        assertThat(reconciliationApi.getBatch(batch.id()).status())
                .isEqualTo(BatchStatus.RECONCILIATION_FAILED);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, AuditOutcome.FAILED, 100))
                .extracting(event -> event.actor())
                .containsExactly("operator-retry", "operator-first");
    }

    @Test
    void startupRecoveryFailsQueuedAndRunningRuns() {
        var queuedBatch = reconciliationApi.importStatement(new StatementUpload(
                "abandoned-queued.csv", csv("CH-ABANDONED-QUEUED,1,2026-01-15T09:30:00Z\n"), "admin"));
        var runningBatch = reconciliationApi.importStatement(new StatementUpload(
                "abandoned-running.csv", csv("CH-ABANDONED-RUNNING,1,2026-01-15T09:30:00Z\n"), "admin"));
        var queuedRunId = UUID.randomUUID();
        var runningRunId = UUID.randomUUID();
        var requestedAt = Instant.parse("2026-01-15T10:00:00Z");
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at)
                VALUES (?, ?, 1, 'QUEUED', 'operator-queued', ?)
                """,
                queuedRunId,
                queuedBatch.id(),
                Timestamp.from(requestedAt));
        jdbcTemplate.update(
                "UPDATE reconciliation.reconciliation_batch SET status = 'RUNNING', started_at = ? WHERE id = ?",
                Timestamp.from(requestedAt),
                runningBatch.id());
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at, started_at)
                VALUES (?, ?, 1, 'RUNNING', 'operator-running', ?, ?)
                """,
                runningRunId,
                runningBatch.id(),
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt));

        eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));

        assertThat(reconciliationApi.findRuns(queuedBatch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.errorMessage()).isEqualTo("Application restarted before run completion");
                });
        assertThat(reconciliationApi.findRuns(runningBatch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.errorMessage()).isEqualTo("Application restarted before run completion");
                });
        assertThat(reconciliationApi.getBatch(queuedBatch.id()))
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(BatchStatus.RECONCILIATION_FAILED);
                    assertThat(batch.errorMessage()).isEqualTo("Application restarted before run completion");
                });
        assertThat(reconciliationApi.getBatch(runningBatch.id()))
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(BatchStatus.RECONCILIATION_FAILED);
                    assertThat(batch.errorMessage()).isEqualTo("Application restarted before run completion");
                });
    }

    @Test
    void importFailedBatchCannotRun() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "cannot-run.csv", csv("CH-BAD,nope,2026-01-15T09:30:00Z\n"), "admin"));

        assertThatThrownBy(() -> reconciliationApi.run(batch.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMPORT_FAILED");
        assertThat(reconciliationApi.getBatch(batch.id()).status()).isEqualTo(BatchStatus.IMPORT_FAILED);
    }

    @Test
    void resolvesDifferenceOnceWithAnAuditRecord() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "resolve.csv", csv("CH-RESOLVE,1,2026-01-15T09:30:00Z\n"), "admin"));
        reconciliationApi.run(batch.id());
        var difference = reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN)
                .get(0);

        var resolved = reconciliationApi.resolve(difference.id(), " 已核对渠道凭证 ", "operator-1");

        assertThat(resolved.resolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolved.resolutionNote()).isEqualTo("已核对渠道凭证");
        assertThat(resolved.resolvedBy()).isEqualTo("operator-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_resolution", Integer.class)).isOne();
        assertThatThrownBy(() -> reconciliationApi.resolve(
                difference.id(), "second", "operator-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved");
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RESOLVE, null, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("operator-1");
                    assertThat(event.aggregateType()).isEqualTo("RECONCILIATION_RESULT");
                    assertThat(event.aggregateId()).isEqualTo(difference.id().toString());
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                });
    }

    @Test
    void cannotResolveMatchedResult() {
        var account = accountsApi.create("Already Matched");
        var payment = paymentsApi.topUp(new TopUpCommand("resolve-match", account.id(), 1));
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "matched.csv", csv(row(payment.channelReference(), 1, payment.occurredAt()) + "\n"), "admin"));
        reconciliationApi.run(batch.id());
        var matched = reconciliationApi.findResults(batch.id(), ResultType.MATCHED, null).get(0);

        assertThatThrownBy(() -> reconciliationApi.resolve(matched.id(), "not needed", "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCHED");
    }

    private static String row(String channelReference, long amountCents, Instant occurredAt) {
        return channelReference + "," + amountCents + "," + occurredAt;
    }

    private ReconciliationRunView awaitRunStatus(UUID batchId, RunStatus expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        ReconciliationRunView latest = null;
        while (System.nanoTime() < deadline) {
            latest = reconciliationApi.findRuns(batchId).get(0);
            if (latest.status() == expected) {
                return latest;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected run status " + expected + " but was "
                + (latest == null ? "missing" : latest.status()));
    }

    private static byte[] csv(String rows) {
        return ("channel_transaction_id,amount_cents,occurred_at\n" + rows)
                .getBytes(StandardCharsets.UTF_8);
    }
}
