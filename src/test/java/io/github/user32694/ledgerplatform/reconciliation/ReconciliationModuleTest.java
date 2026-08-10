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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
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
        "DELETE FROM messaging.outbox_event",
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
        "DELETE FROM messaging.outbox_event",
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
class ReconciliationModuleTest {
    private static final UUID DEFAULT_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ALIPAY_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Autowired ReconciliationApi reconciliationApi;
    @Autowired ReconciliationRulesApi reconciliationRulesApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired AccountsApi accountsApi;
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired ConfigurableApplicationContext applicationContext;
    @Autowired JobExplorer jobExplorer;
    @Autowired JobRepository jobRepository;

    @BeforeEach
    void resetReconciliationRuleFixtureBeforeTest() {
        resetReconciliationRuleFixture();
    }

    @AfterEach
    void resetReconciliationRuleFixtureAfterTest() {
        resetReconciliationRuleFixture();
    }

    @Test
    void queuesOneActiveRunAndListsItAsTheFirstAttempt() throws InterruptedException {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "queued.csv", csv("CH-QUEUED,1,2026-01-15T09:30:00Z\n"), "admin"));

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
                .extracting(event -> event.summary())
                .containsExactly("对账运行成功", "对账运行已启动");
    }

    @Test
    void completesQueuedRunAsynchronouslyAndAuditsTheRequester() throws InterruptedException {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "async.csv", csv("CH-ASYNC,1,2026-01-15T09:30:00Z\n"), "admin"));

        var queued = reconciliationApi.startRun(batch.id(), "operator-async");
        var completed = awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);

        assertThat(queued.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(completed.requestedBy()).isEqualTo("operator-async");
        assertThat(reconciliationApi.getBatch(batch.id()).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN))
                .hasSize(1);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, AuditOutcome.SUCCEEDED, 100))
                .extracting(event -> event.actor(), event -> event.summary(), event -> event.correlationReference())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "operator-async", "对账运行成功", completed.id().toString()),
                        org.assertj.core.groups.Tuple.tuple(
                                "operator-async", "对账运行已启动", completed.id().toString()));
    }

    @Test
    void importsValidStatementAtomically() {
        var resolved = reconciliationRulesApi.resolvePublishedVersion("ALIPAY");
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "statement.csv", csv("CH-1,12500,2026-01-15T09:30:00Z\nCH-2,7500,2026-01-15T10:45:00Z\n"), "admin"));

        assertThat(batch.status()).isEqualTo(BatchStatus.IMPORTED);
        assertThat(batch.totalRows()).isEqualTo(2);
        assertThat(batch.periodStart()).isEqualTo(java.time.Instant.parse("2026-01-15T09:30:00Z"));
        assertThat(batch.periodEnd()).isEqualTo(java.time.Instant.parse("2026-01-15T10:45:00Z"));
        assertThat(batch.channelCode()).isEqualTo("ALIPAY");
        assertThat(batch.channelDisplayName()).isEqualTo("支付宝");
        assertThat(batch.ruleVersionId()).isEqualTo(resolved.id());
        assertThat(batch.ruleVersionNumber()).isEqualTo(resolved.versionNumber());
        assertThat(batch.amountToleranceCents()).isEqualTo(resolved.amountToleranceCents());
        assertThat(batch.queryWindowHours()).isEqualTo(resolved.queryWindowHours());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT channel_id FROM reconciliation.reconciliation_batch WHERE id = ?",
                UUID.class, batch.id()))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rule_version_id FROM reconciliation.reconciliation_batch WHERE id = ?",
                UUID.class, batch.id()))
                .isEqualTo(resolved.id());
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
    void rejectsInactiveAndUnknownChannelsBeforeParsing() {
        reconciliationRulesApi.setChannelActive("ALIPAY", false, "rule-admin");
        var content = csv("CH-REJECTED,not-an-amount,2026-01-15T09:30:00Z\n");

        assertThatThrownBy(() -> reconciliationApi.importStatement(
                        new StatementUpload("ALIPAY", "inactive.csv", content, "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("对账渠道未启用: ALIPAY");
        assertThatThrownBy(() -> reconciliationApi.importStatement(
                        new StatementUpload("UNKNOWN", "unknown.csv", content, "admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("对账渠道不存在: UNKNOWN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_batch", Integer.class)).isZero();
    }

    @Test
    void locksChannelOverrideAndKeepsItAfterPublishingALaterVersion() {
        var firstPublished = reconciliationRulesApi.publish(
                ALIPAY_RULE_ID,
                reconciliationRulesApi.saveDraft(
                        ALIPAY_RULE_ID,
                        new ReconciliationRuleDraftCommand(25, 2, "rule-editor")).id(),
                25,
                2,
                "rule-publisher");
        var resolvedDefault = reconciliationRulesApi.resolvePublishedVersion("WECHAT_PAY");

        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "locked.csv", csv("CH-LOCKED,1,2026-01-15T09:30:00Z\n"), "admin"));

        var secondPublished = reconciliationRulesApi.publish(
                ALIPAY_RULE_ID,
                reconciliationRulesApi.saveDraft(
                        ALIPAY_RULE_ID,
                        new ReconciliationRuleDraftCommand(75, 6, "rule-editor-2")).id(),
                75,
                6,
                "rule-publisher-2");
        var reloaded = reconciliationApi.getBatch(batch.id());

        assertThat(firstPublished.sourceScope()).isEqualTo(RuleScopeType.CHANNEL);
        assertThat(firstPublished.ruleId()).isEqualTo(ALIPAY_RULE_ID);
        assertThat(firstPublished.ruleId()).isNotEqualTo(resolvedDefault.ruleId());
        assertThat(secondPublished.id()).isNotEqualTo(firstPublished.id());
        assertThat(reloaded.ruleVersionId()).isEqualTo(firstPublished.id());
        assertThat(reloaded.ruleVersionNumber()).isEqualTo(firstPublished.versionNumber());
        assertThat(reloaded.amountToleranceCents()).isEqualTo(25);
        assertThat(reloaded.queryWindowHours()).isEqualTo(2);
    }

    @Test
    void resolvesPaymentsAcrossLockedQueryWindowForResultsAndCaseLookup() {
        var published = reconciliationRulesApi.publish(
                ALIPAY_RULE_ID,
                reconciliationRulesApi.saveDraft(
                        ALIPAY_RULE_ID,
                        new ReconciliationRuleDraftCommand(0, 2, "rule-editor")).id(),
                0,
                2,
                "rule-publisher");
        var account = accountsApi.create("Window Customer");
        var payment = paymentsApi.topUp(new TopUpCommand("window-payment", account.id(), 300));
        var statementAt = payment.occurredAt().plus(1, java.time.temporal.ChronoUnit.HOURS);
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "window.csv", csv(row("CHANNEL-ONLY", 1, statementAt)), "admin"));
        var resultId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, payment_id, result_type, resolution_status, created_at)
                VALUES (?, ?, NULL, ?, 'INTERNAL_ONLY', 'OPEN', CURRENT_TIMESTAMP)
                """, resultId, batch.id(), payment.id());

        assertThat(batch.queryStart()).isEqualTo(statementAt.minus(2, java.time.temporal.ChronoUnit.HOURS));
        assertThat(batch.queryEnd()).isEqualTo(statementAt.plus(2, java.time.temporal.ChronoUnit.HOURS));
        assertThat(batch.ruleVersionId()).isEqualTo(published.id());
        assertThat(reconciliationApi.findResults(batch.id(), ResultType.INTERNAL_ONLY, null))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.paymentId()).isEqualTo(payment.id());
                    assertThat(result.internalAmountCents()).isEqualTo(300);
                    assertThat(result.occurredAt()).isEqualTo(payment.occurredAt());
                });
        assertThat(reconciliationApi.findCases(ResultType.INTERNAL_ONLY, ResolutionStatus.OPEN, null))
                .singleElement()
                .satisfies(caseView -> {
                    assertThat(caseView.paymentId()).isEqualTo(payment.id());
                    assertThat(caseView.internalAmountCents()).isEqualTo(300);
                });
        assertThat(reconciliationApi.getResult(resultId).caseView().internalAmountCents())
                .isEqualTo(300);
    }

    @Test
    void returnsExistingBatchForRepeatedFileHashEvenWhenChannelChangesBecauseHashIdempotencyIsFileLevel() {
        var content = csv("CH-IDEMPOTENT,1,2026-01-15T09:30:00Z\n");
        var first = reconciliationApi.importStatement(new StatementUpload("ALIPAY", "first.csv", content, "admin"));
        var repeated = reconciliationApi.importStatement(new StatementUpload("WECHAT_PAY", "second.csv", content, "other"));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.channelCode()).isEqualTo(first.channelCode());
        assertThat(repeated.ruleVersionId()).isEqualTo(first.ruleVersionId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_batch", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry", Integer.class)).isOne();
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_IMPORT, null, 100))
                .hasSize(1);
    }

    @Test
    void rejectsInvalidChannelsBeforeReturningAnExistingBatchForTheFileHash() {
        var content = csv("CH-INVALID-CHANNEL,1,2026-01-15T09:30:00Z\n");
        reconciliationApi.importStatement(new StatementUpload("ALIPAY", "first.csv", content, "admin"));
        reconciliationRulesApi.setChannelActive("WECHAT_PAY", false, "rule-admin");

        assertThatThrownBy(() -> reconciliationApi.importStatement(
                        new StatementUpload("UNKNOWN", "unknown.csv", content, "admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("对账渠道不存在: UNKNOWN");
        assertThatThrownBy(() -> reconciliationApi.importStatement(
                        new StatementUpload("", "blank.csv", content, "admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Channel code is invalid");
        assertThatThrownBy(() -> reconciliationApi.importStatement(
                        new StatementUpload("WECHAT_PAY", "inactive.csv", content, "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("对账渠道未启用: WECHAT_PAY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_batch", Integer.class)).isOne();
    }

    @Test
    void retainsOnlyFailureMetadataWhenCsvIsInvalid() {
        var content = csv("CH-BAD,not-an-amount,2026-01-15T09:30:00Z\n");
        var resolved = reconciliationRulesApi.resolvePublishedVersion("ALIPAY");
        var batch = reconciliationApi.importStatement(new StatementUpload("ALIPAY", "bad.csv", content, "admin"));

        assertThat(batch.status()).isEqualTo(BatchStatus.IMPORT_FAILED);
        assertThat(batch.errorMessage()).isNotBlank();
        assertThat(batch.periodStart()).isNull();
        assertThat(batch.channelCode()).isEqualTo("ALIPAY");
        assertThat(batch.channelDisplayName()).isEqualTo("支付宝");
        assertThat(batch.ruleVersionId()).isEqualTo(resolved.id());
        assertThat(batch.ruleVersionNumber()).isEqualTo(resolved.versionNumber());
        assertThat(batch.amountToleranceCents()).isEqualTo(resolved.amountToleranceCents());
        assertThat(batch.queryWindowHours()).isEqualTo(resolved.queryWindowHours());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT channel_id FROM reconciliation.reconciliation_batch WHERE id = ?",
                UUID.class, batch.id()))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rule_version_id FROM reconciliation.reconciliation_batch WHERE id = ?",
                UUID.class, batch.id()))
                .isEqualTo(resolved.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry", Integer.class)).isZero();
        assertThat(reconciliationApi.importStatement(new StatementUpload("ALIPAY", "bad-again.csv", content, "admin")).id())
                .isEqualTo(batch.id());
        assertThatThrownBy(batch::queryStart).isInstanceOf(IllegalStateException.class);
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
                "ALIPAY", "first.csv", csv("CH-GLOBAL,1,2026-01-15T09:30:00Z\n"), "admin"));
        var rejected = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "second.csv", csv("CH-GLOBAL,2,2026-01-15T10:30:00Z\n"), "admin"));

        assertThat(rejected.status()).isEqualTo(BatchStatus.IMPORT_FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.channel_statement_entry", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_batch", Integer.class)).isEqualTo(2);
    }

    @Test
    void computesSha256AsLowercaseHex() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "hash.csv", csv("CH-HASH,1,2026-01-15T09:30:00Z\n"), "admin"));

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
                new StatementUpload("ALIPAY", "matching.csv", csv(rows), "admin"));
        var run = runToCompletion(batch.id(), "operator-match");
        var completed = reconciliationApi.getBatch(batch.id());

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
                .filteredOn(event -> event.summary().equals("对账运行成功"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("operator-match");
                    assertThat(event.aggregateId()).isEqualTo(batch.id().toString());
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                });
        assertReconciliationCompletedEvent(completed, run);
    }

    @Test
    void completedBatchRunIsIdempotent() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "rerun.csv", csv("CH-RERUN,1,2026-01-15T09:30:00Z\n"), "admin"));
        var firstRun = runToCompletion(batch.id(), "operator-first");
        var completed = reconciliationApi.getBatch(batch.id());
        var firstResultIds = reconciliationApi.findResults(batch.id(), null, null).stream()
                .map(ReconciliationResultView::id)
                .toList();

        var repeated = reconciliationApi.startRun(batch.id(), "operator-repeat");

        assertThat(repeated).isEqualTo(firstRun);
        assertThat(reconciliationApi.getBatch(batch.id())).isEqualTo(completed);
        assertThat(reconciliationApi.findResults(batch.id(), null, null).stream()
                .map(ReconciliationResultView::id)
                .toList()).containsExactlyElementsOf(firstResultIds);
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, null, 100))
                .extracting(event -> event.summary())
                .containsExactly("对账运行成功", "对账运行已启动");
    }

    @Test
    void repeatedStartReturnsActiveAsyncAttemptWithoutChangingState() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "sync-active.csv", csv("CH-SYNC-ACTIVE,1,2026-01-15T09:30:00Z\n"), "admin"));
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

        var repeated = reconciliationApi.startRun(batch.id(), "operator-repeat");

        assertThat(reconciliationApi.getBatch(batch.id()).status()).isEqualTo(BatchStatus.IMPORTED);
        assertThat(repeated.id()).isEqualTo(runId);
        assertThat(repeated.requestedBy()).isEqualTo("operator-async");
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
    void auditsFailedReconciliationRun() throws InterruptedException {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "failed-run.csv", csv("CH-FAILED-RUN,1,2026-01-15T09:30:00Z\n"), "admin"));

        reconciliationApi.startRun(batch.id(), "operator-failed");
        var failed = awaitRunStatus(batch.id(), RunStatus.FAILED);

        assertThat(failed.errorMessage()).contains("DataIntegrityViolationException");
        assertThat(reconciliationApi.getBatch(batch.id()).status())
                .isEqualTo(BatchStatus.RECONCILIATION_FAILED);
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_RUN, AuditOutcome.FAILED, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("operator-failed");
                    assertThat(event.aggregateId()).isEqualTo(batch.id().toString());
                });
        assertThat(reconciliationEventCount(batch.id())).isZero();
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
                "ALIPAY", "failed-async.csv", csv("CH-FAILED-ASYNC,1,2026-01-15T09:30:00Z\n"), "admin"));

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
    void startupRecoveryResubmitsQueuedAndFailsUnknownRunningExecutions() throws InterruptedException {
        var queuedBatch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "abandoned-queued.csv", csv("CH-ABANDONED-QUEUED,1,2026-01-15T09:30:00Z\n"), "admin"));
        var runningBatch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "abandoned-running.csv", csv("CH-ABANDONED-RUNNING,1,2026-01-15T09:30:00Z\n"), "admin"));
        var currentProcessBatch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "current-process.csv", csv("CH-CURRENT-PROCESS,1,2026-01-15T09:30:00Z\n"), "admin"));
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
                    (id, batch_id, attempt_number, status, requested_by, requested_at, started_at,
                     batch_job_execution_id)
                VALUES (?, ?, 1, 'RUNNING', 'operator-running', ?, ?, 999999)
                """,
                runningRunId,
                runningBatch.id(),
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt));
        var currentProcessRunId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at)
                VALUES (?, ?, 1, 'QUEUED', 'current-process-operator', ?)
                """,
                currentProcessRunId,
                currentProcessBatch.id(),
                Timestamp.from(Instant.now()));

        eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));

        var recoveredQueued = awaitRunStatus(queuedBatch.id(), RunStatus.SUCCEEDED);
        assertThat(recoveredQueued.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(reconciliationApi.findRuns(runningBatch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.errorMessage()).isEqualTo("Application restarted before run completion");
                });
        var completedQueuedBatch = reconciliationApi.getBatch(queuedBatch.id());
        assertThat(completedQueuedBatch)
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
                    assertThat(batch.errorMessage()).isNull();
                });
        assertThat(reconciliationApi.getBatch(runningBatch.id()))
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(BatchStatus.RECONCILIATION_FAILED);
                    assertThat(batch.errorMessage()).isEqualTo("Application restarted before run completion");
                });
        assertThat(reconciliationApi.findRuns(currentProcessBatch.id()))
                .singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(RunStatus.QUEUED));
        assertThat(reconciliationApi.getBatch(currentProcessBatch.id()).status())
                .isEqualTo(BatchStatus.IMPORTED);
        assertReconciliationCompletedEvent(completedQueuedBatch, recoveredQueued);
        assertThat(reconciliationEventCount(runningBatch.id())).isZero();
        assertThat(reconciliationEventCount(currentProcessBatch.id())).isZero();
    }

    @Test
    void startupRecoveryReusesAnExecutionAcceptedBeforeBeforeJob() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "accepted-before-listener.csv",
                csv("CH-ACCEPTED-BEFORE-LISTENER,1,2026-01-15T09:30:00Z\n"), "admin"));
        var runId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at)
                VALUES (?, ?, 1, 'QUEUED', 'operator-recovery', ?)
                """,
                runId,
                batch.id(),
                Timestamp.from(Instant.parse("2026-01-15T10:00:00Z")));
        var parameters = new JobParametersBuilder()
                .addString("runId", runId.toString(), true)
                .toJobParameters();
        var accepted = jobRepository.createJobExecution("reconciliationJob", parameters);

        eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));

        var recovered = awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);
        assertThat(recovered.batchJobInstanceId())
                .isEqualTo(accepted.getJobInstance().getInstanceId());
        assertThat(jobExplorer.getJobExecutions(accepted.getJobInstance())).hasSize(2);
    }

    @Test
    void startupRecoveryTerminatesAnActiveExecutionForAnAlreadyFailedRun() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "failed-domain-active-execution.csv",
                csv("CH-FAILED-DOMAIN-ACTIVE,1,2026-01-15T09:30:00Z\n"), "admin"));
        var runId = UUID.randomUUID();
        var parameters = new JobParametersBuilder()
                .addString("runId", runId.toString(), true)
                .toJobParameters();
        var activeExecution = jobRepository.createJobExecution("reconciliationJob", parameters);
        var staleAt = Timestamp.from(Instant.parse("2026-01-15T10:00:00Z"));
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_batch
                SET status = 'RECONCILIATION_FAILED', started_at = ?, completed_at = ?,
                    error_message = 'recovery interrupted'
                WHERE id = ?
                """, staleAt, staleAt, batch.id());
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at,
                     started_at, completed_at, error_message, batch_job_instance_id,
                     batch_job_execution_id, restart_count)
                VALUES (?, ?, 1, 'FAILED', 'operator-recovery', ?, ?, ?,
                        'recovery interrupted', ?, ?, 1)
                """,
                runId,
                batch.id(),
                staleAt,
                staleAt,
                staleAt,
                activeExecution.getJobInstance().getInstanceId(),
                activeExecution.getId());

        eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));

        assertThat(jobExplorer.getJobExecution(activeExecution.getId()).getStatus())
                .isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
        assertThat(reconciliationApi.findRuns(batch.id()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.batchJobExecutionId()).isEqualTo(activeExecution.getId());
                    assertThat(run.restartCount()).isOne();
                });
        assertThat(jobExplorer.getJobExecutions(activeExecution.getJobInstance())).hasSize(1);
    }

    @Test
    void managesClaimReleaseAndResolutionWithOrderedEvidence() {
        var difference = createChannelOnlyDifference("lifecycle");

        var claimed = reconciliationApi.claim(difference.id(), "operator-1");
        var repeated = reconciliationApi.claim(difference.id(), "operator-1");

        assertThat(claimed.resolutionStatus()).isEqualTo(ResolutionStatus.CLAIMED);
        assertThat(claimed.assignedTo()).isEqualTo("operator-1");
        assertThat(claimed.claimedAt()).isNotNull();
        assertThat(repeated).isEqualTo(claimed);
        assertThat(reconciliationApi.findCaseEvents(difference.id())).hasSize(1);
        assertThat(caseAuditCount()).isOne();

        var released = reconciliationApi.release(difference.id(), "operator-1");
        assertThat(released.resolutionStatus()).isEqualTo(ResolutionStatus.OPEN);
        assertThat(released.assignedTo()).isNull();
        assertThat(released.claimedAt()).isNull();

        reconciliationApi.claim(difference.id(), "operator-1");
        var resolved = reconciliationApi.resolve(
                difference.id(),
                ResolutionCode.INTERNAL_CONFIRMED,
                "  checked against internal records  ",
                "operator-1");

        assertThat(resolved.resolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolved.assignedTo()).isEqualTo("operator-1");
        assertThat(resolved.resolutionNote()).isEqualTo("checked against internal records");
        assertThat(resolved.resolvedBy()).isEqualTo("operator-1");
        assertThatThrownBy(() -> reconciliationApi.claim(difference.id(), "operator-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESOLVED");

        assertThat(reconciliationApi.findCaseEvents(difference.id()))
                .extracting(
                        ReconciliationCaseEventView::action,
                        ReconciliationCaseEventView::actor,
                        ReconciliationCaseEventView::resolutionCode,
                        ReconciliationCaseEventView::note)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CLAIMED", "operator-1", null, null),
                        org.assertj.core.groups.Tuple.tuple("RELEASED", "operator-1", null, null),
                        org.assertj.core.groups.Tuple.tuple("CLAIMED", "operator-1", null, null),
                        org.assertj.core.groups.Tuple.tuple(
                                "RESOLVED",
                                "operator-1",
                                ResolutionCode.INTERNAL_CONFIRMED,
                                "checked against internal records"));
        assertThat(auditApi.findRecent(null, null, 100).stream()
                        .filter(event -> event.action().name().startsWith("RECONCILIATION_CASE_")))
                .extracting(event -> event.action(), event -> event.actor(),
                        event -> event.aggregateType(), event -> event.aggregateId(),
                        event -> event.correlationReference())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                AuditAction.RECONCILIATION_CASE_RESOLVE,
                                "operator-1", "RECONCILIATION_RESULT",
                                difference.id().toString(), difference.batchId().toString()),
                        org.assertj.core.groups.Tuple.tuple(
                                AuditAction.RECONCILIATION_CASE_CLAIM,
                                "operator-1", "RECONCILIATION_RESULT",
                                difference.id().toString(), difference.batchId().toString()),
                        org.assertj.core.groups.Tuple.tuple(
                                AuditAction.RECONCILIATION_CASE_RELEASE,
                                "operator-1", "RECONCILIATION_RESULT",
                                difference.id().toString(), difference.batchId().toString()),
                        org.assertj.core.groups.Tuple.tuple(
                                AuditAction.RECONCILIATION_CASE_CLAIM,
                                "operator-1", "RECONCILIATION_RESULT",
                                difference.id().toString(), difference.batchId().toString()));
    }

    @Test
    void rejectsNonOwnerInvalidAndMatchedTransitionsWithoutEvidence() {
        var difference = createChannelOnlyDifference("rejected");
        reconciliationApi.claim(difference.id(), "operator-1");

        assertThatThrownBy(() -> reconciliationApi.claim(difference.id(), "operator-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assigned to another operator");
        assertThatThrownBy(() -> reconciliationApi.release(difference.id(), "operator-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assigned to another operator");
        assertThatThrownBy(() -> reconciliationApi.resolve(
                        difference.id(), ResolutionCode.OTHER, "checked", "operator-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assigned to another operator");
        assertThatThrownBy(() -> reconciliationApi.resolve(
                        difference.id(), null, "checked", "operator-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resolution code");
        assertThatThrownBy(() -> reconciliationApi.resolve(
                        difference.id(), ResolutionCode.OTHER, "  ", "operator-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resolution note");

        assertThat(reconciliationApi.findCaseEvents(difference.id())).hasSize(1);
        assertThat(caseAuditCount()).isOne();

        var account = accountsApi.create("Matched Claim Customer");
        var payment = paymentsApi.topUp(new TopUpCommand("matched-claim-payment", account.id(), 1));
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "matched-claim.csv",
                csv(row(payment.channelReference(), 1, payment.occurredAt()) + "\n"),
                "admin"));
        runToCompletion(batch.id(), "operator-matched-claim");
        var matched = reconciliationApi.findResults(batch.id(), ResultType.MATCHED, null).get(0);

        assertThatThrownBy(() -> reconciliationApi.claim(matched.id(), "operator-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCHED");
        assertThat(reconciliationApi.findCaseEvents(matched.id())).isEmpty();
        assertThat(caseAuditCount()).isOne();
    }

    @Test
    void concurrentClaimsCannotOverwriteTheOwner() throws Exception {
        var difference = createChannelOnlyDifference("concurrent-claim");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Callable<ClaimAttempt> first = concurrentClaim(
                    difference.id(), "operator-1", ready, start);
            Callable<ClaimAttempt> second = concurrentClaim(
                    difference.id(), "operator-2", ready, start);
            var firstFuture = executor.submit(first);
            var secondFuture = executor.submit(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var attempts = List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS));

            assertThat(attempts).filteredOn(attempt -> attempt.error() == null).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> attempt.error() != null)
                    .singleElement()
                    .extracting(ClaimAttempt::error)
                    .isInstanceOf(IllegalStateException.class);
            var winner = attempts.stream()
                    .filter(attempt -> attempt.result() != null)
                    .findFirst()
                    .orElseThrow()
                    .result();
            var persisted = reconciliationApi.findResults(
                    difference.batchId(), difference.resultType(), ResolutionStatus.CLAIMED).get(0);
            assertThat(persisted.assignedTo()).isEqualTo(winner.assignedTo());
            assertThat(reconciliationApi.findCaseEvents(difference.id())).hasSize(1);
            assertThat(caseAuditCount()).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void importFailedBatchCannotRun() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "cannot-run.csv", csv("CH-BAD,nope,2026-01-15T09:30:00Z\n"), "admin"));

        assertThatThrownBy(() -> reconciliationApi.startRun(batch.id(), "operator-import-failed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMPORT_FAILED");
        assertThat(reconciliationApi.getBatch(batch.id()).status()).isEqualTo(BatchStatus.IMPORT_FAILED);
    }

    @Test
    void resolvesClaimedDifferenceOnceWithAnAuditRecord() {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "resolve.csv", csv("CH-RESOLVE,1,2026-01-15T09:30:00Z\n"), "admin"));
        runToCompletion(batch.id(), "operator-resolve");
        var difference = reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN)
                .get(0);

        reconciliationApi.claim(difference.id(), "operator-1");
        var resolved = reconciliationApi.resolve(
                difference.id(), ResolutionCode.OTHER, " 已核对渠道凭证 ", "operator-1");

        assertThat(resolved.resolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolved.resolutionNote()).isEqualTo("已核对渠道凭证");
        assertThat(resolved.resolvedBy()).isEqualTo("operator-1");
        var caseEvents = reconciliationApi.findCaseEvents(difference.id());
        assertThat(caseEvents)
                .extracting(ReconciliationCaseEventView::action)
                .containsExactly("CLAIMED", "RESOLVED");
        assertThat(caseEvents.get(0).createdAt()).isBefore(caseEvents.get(1).createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation.reconciliation_resolution", Integer.class)).isOne();
        assertThatThrownBy(() -> reconciliationApi.resolve(
                difference.id(), ResolutionCode.OTHER, "second", "operator-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESOLVED");
        assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_CASE_RESOLVE, null, 100))
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
                "ALIPAY", "matched.csv", csv(row(payment.channelReference(), 1, payment.occurredAt()) + "\n"), "admin"));
        runToCompletion(batch.id(), "operator-matched");
        var matched = reconciliationApi.findResults(batch.id(), ResultType.MATCHED, null).get(0);

        assertThatThrownBy(() -> reconciliationApi.resolve(
                matched.id(), ResolutionCode.OTHER, "not needed", "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCHED");
    }

    @Test
    void returnsCaseWorkbenchRowsWithFiltersAndDetailsTimeline() {
        var older = createChannelOnlyDifference("query-older");
        reconciliationApi.claim(older.id(), "operator-older");
        var newer = createChannelOnlyDifference("query-newer");
        reconciliationApi.claim(newer.id(), "operator-newer");
        reconciliationApi.release(newer.id(), "operator-newer");
        reconciliationApi.claim(newer.id(), "operator-newer");
        reconciliationApi.resolve(newer.id(), ResolutionCode.CHANNEL_CONFIRMED, "confirmed", "operator-newer");

        assertThat(reconciliationApi.findCases(null, null, null))
                .extracting(ReconciliationCaseView::id)
                .containsExactly(newer.id(), older.id());
        assertThat(reconciliationApi.findCases(ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN, null))
                .isEmpty();
        assertThat(reconciliationApi.findCases(null, ResolutionStatus.CLAIMED, "operator-older"))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.id()).isEqualTo(older.id());
                    assertThat(view.batchFileName()).isEqualTo("query-older.csv");
                    assertThat(view.channelAmountCents()).isEqualTo(1L);
                    assertThat(view.internalAmountCents()).isNull();
                    assertThat(view.differenceAmountCents()).isNull();
                    assertThat(view.assignedTo()).isEqualTo("operator-older");
                });

        var details = reconciliationApi.getResult(newer.id());
        assertThat(details.caseView().id()).isEqualTo(newer.id());
        assertThat(details.timeline())
                .extracting(ReconciliationCaseEventView::action)
                .containsExactly("RESOLVED", "CLAIMED", "RELEASED", "CLAIMED");
    }

    @Test
    void aggregatesCaseProgressAcrossBatches() {
        var open = createChannelOnlyDifference("progress-open");
        var resolved = createChannelOnlyDifference("progress-resolved");
        reconciliationApi.claim(resolved.id(), "progress-operator");
        reconciliationApi.resolve(
                resolved.id(), ResolutionCode.CHANNEL_CONFIRMED, "confirmed", "progress-operator");

        assertThat(reconciliationApi.findCaseProgresses())
                .extracting(
                        ReconciliationCaseProgress::batchId,
                        ReconciliationCaseProgress::resolvedCount,
                        ReconciliationCaseProgress::totalCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(open.batchId(), 0L, 1L),
                        org.assertj.core.groups.Tuple.tuple(resolved.batchId(), 1L, 1L));
    }

    @Test
    void reportsEmptyOperationsSummaryWithoutCompletedBatches() {
        var summary = reconciliationApi.getOperationsSummary();

        assertThat(summary.latestCompletedBatchId()).isNull();
        assertThat(summary.completedMatchRate()).isNull();
        assertThat(summary.openCount()).isZero();
        assertThat(summary.claimedCount()).isZero();
        assertThat(summary.failedRunCount()).isZero();
    }

    @Test
    void reportsLatestMatchRateOpenClaimedAndFailedCounts() {
        var account = accountsApi.create("Summary Customer");
        var payment = paymentsApi.topUp(new TopUpCommand("summary-match", account.id(), 100));
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", "summary.csv",
                csv(row(payment.channelReference(), 100, payment.occurredAt())
                        + "\nSUMMARY-DIFF,50,2026-01-15T10:00:00Z\n"),
                "admin"));
        runToCompletion(batch.id(), "operator-summary");
        var difference = reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN)
                .get(0);
        reconciliationApi.claim(difference.id(), "summary-operator");

        var failedRunId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at,
                     completed_at, error_message)
                VALUES (?, ?, 2, 'FAILED', 'summary-operator', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, 'failed for summary')
                """, failedRunId, batch.id());

        var summary = reconciliationApi.getOperationsSummary();
        assertThat(summary.latestCompletedBatchId()).isEqualTo(batch.id());
        assertThat(summary.completedMatchRate()).isEqualTo(50.0);
        assertThat(summary.openCount()).isZero();
        assertThat(summary.claimedCount()).isOne();
        assertThat(summary.failedRunCount()).isOne();
    }

    private static String row(String channelReference, long amountCents, Instant occurredAt) {
        return channelReference + "," + amountCents + "," + occurredAt;
    }

    private void resetReconciliationRuleFixture() {
        jdbcTemplate.execute("DELETE FROM audit.audit_event");
        jdbcTemplate.execute(
                "TRUNCATE reconciliation.reconciliation_rule_version, "
                        + "reconciliation.reconciliation_rule, "
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
        jdbcTemplate.update(
                "UPDATE reconciliation.reconciliation_rule SET active_version_id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000201"), DEFAULT_RULE_ID);
    }

    private ReconciliationResultView createChannelOnlyDifference(String suffix) {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", suffix + ".csv",
                csv("CH-" + suffix.toUpperCase() + ",1,2026-01-15T09:30:00Z\n"),
                "admin"));
        runToCompletion(batch.id(), "operator-" + suffix);
        return reconciliationApi.findResults(
                batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN).get(0);
    }

    private Callable<ClaimAttempt> concurrentClaim(
            UUID resultId, String operator, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return new ClaimAttempt(reconciliationApi.claim(resultId, operator), null);
            } catch (RuntimeException exception) {
                return new ClaimAttempt(null, exception);
            }
        };
    }

    private long caseAuditCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit.audit_event
                WHERE action IN (
                    'RECONCILIATION_CASE_CLAIM',
                    'RECONCILIATION_CASE_RELEASE',
                    'RECONCILIATION_CASE_RESOLVE')
                """, Long.class);
    }

    private void assertReconciliationCompletedEvent(
            ReconciliationBatchView batch, ReconciliationRunView run) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, schema_version,
                       payload = jsonb_build_object(
                           'batchId', ?,
                           'runId', ?,
                           'matchedRows', CAST(? AS integer),
                           'differenceRows', CAST(? AS integer)
                       ) AS payload_matches
                FROM messaging.outbox_event
                WHERE aggregate_type = 'RECONCILIATION_BATCH' AND aggregate_id = ?
                """, batch.id().toString(), run.id().toString(), batch.matchedRows(),
                batch.differenceRows(), batch.id().toString());

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("event_type")).isEqualTo("RECONCILIATION_COMPLETED");
            assertThat(row.get("aggregate_type")).isEqualTo("RECONCILIATION_BATCH");
            assertThat(row.get("aggregate_id")).isEqualTo(batch.id().toString());
            assertThat(row.get("schema_version")).isEqualTo(1);
            assertThat(row.get("payload_matches")).isEqualTo(true);
        });
    }

    private long reconciliationEventCount(UUID batchId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging.outbox_event
                WHERE aggregate_type = 'RECONCILIATION_BATCH' AND aggregate_id = ?
                """, Long.class, batchId.toString());
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

    private ReconciliationRunView runToCompletion(UUID batchId, String operator) {
        reconciliationApi.startRun(batchId, operator);
        try {
            return awaitRunStatus(batchId, RunStatus.SUCCEEDED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for reconciliation run", exception);
        }
    }

    private static byte[] csv(String rows) {
        return ("channel_transaction_id,amount_cents,occurred_at\n" + rows)
                .getBytes(StandardCharsets.UTF_8);
    }

    private record ClaimAttempt(ReconciliationResultView result, RuntimeException error) {}
}
