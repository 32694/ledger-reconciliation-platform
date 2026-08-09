package io.github.user32694.ledgerplatform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(statements = {
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
class ReconciliationWebTest {
    @Autowired MockMvc mockMvc;
    @Autowired ReconciliationApi reconciliationApi;
    @Autowired AccountsApi accountsApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("reconciliationTaskExecutor") ThreadPoolTaskExecutor taskExecutor;

    @Test
    void redirectsAnonymousUserToLogin() throws Exception {
        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersChineseListAndImportPages() throws Exception {
        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-list"))
                .andExpect(content().string(containsString("自动对账")))
                .andExpect(content().string(containsString("导入渠道账单")));
        mockMvc.perform(get("/admin/reconciliation/import"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-import"))
                .andExpect(content().string(containsString("channel_transaction_id,amount_cents,occurred_at")))
                .andExpect(content().string(containsString("模拟渠道")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void requiresCsrfAndUploadsStatement() throws Exception {
        byte[] csv = "channel_transaction_id,amount_cents,occurred_at\nCH-WEB,1,2026-01-15T09:30:00Z\n"
                .getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "statement.csv", "text/csv", csv);

        mockMvc.perform(multipart("/admin/reconciliation/import").file(file))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/admin/reconciliation/import").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/reconciliation/*"));
    }

    @Test
    @WithMockUser(username = "run-admin", roles = "ADMIN")
    void startsBatchForAuthenticatedOperatorWithoutWaitingForExecution() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "routes.csv", "channel_transaction_id,amount_cents,occurred_at\nCH-ROUTE,1,2026-01-15T09:30:00Z\n"
                        .getBytes(StandardCharsets.UTF_8), "admin"));
        var workersStarted = new CountDownLatch(2);
        var releaseWorkers = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < 2; worker++) {
                taskExecutor.execute(() -> {
                    workersStarted.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(workersStarted.await(2, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/admin/reconciliation/{batchId}/run", batch.id()).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/reconciliation/" + batch.id()));

            assertThat(reconciliationApi.findRuns(batch.id()))
                    .singleElement()
                    .satisfies(run -> {
                        assertThat(run.requestedBy()).isEqualTo("run-admin");
                        assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
                    });
        } finally {
            releaseWorkers.countDown();
        }
        awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void activeRunStatusFragmentPollsEveryTwoSeconds() throws Exception {
        var activeBatch = importBatch("active.csv", "CH-ACTIVE");
        insertRun(activeBatch.id(), 1, "QUEUED", "queue-admin", 0, 0, null);

        mockMvc.perform(get("/admin/reconciliation/{batchId}/run-status", activeBatch.id()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fragments/reconciliation-run-status :: status"))
                .andExpect(content().string(containsString(
                        "hx-get=\"/admin/reconciliation/" + activeBatch.id() + "/run-status\"")))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")))
                .andExpect(content().string(containsString("hx-target=\"this\"")))
                .andExpect(content().string(containsString("hx-swap=\"outerHTML\"")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void terminalRunStatusFragmentRequestsPageRefreshWithoutPolling() throws Exception {
        var terminalBatch = importBatch("terminal.csv", "CH-TERMINAL");
        insertRun(terminalBatch.id(), 1, "FAILED", "failure-admin", 0, 0, "timeout");
        mockMvc.perform(get("/admin/reconciliation/{batchId}/run-status", terminalBatch.id()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Refresh", "true"))
                .andExpect(content().string(containsString("执行失败")))
                .andExpect(content().string(not(containsString("hx-get"))))
                .andExpect(content().string(not(containsString("hx-trigger"))));
    }

    @Test
    void protectsRunRouteFromAnonymousUsers() throws Exception {
        mockMvc.perform(post("/admin/reconciliation/{batchId}/run", UUID.randomUUID()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void protectsRunRouteWithCsrf() throws Exception {
        mockMvc.perform(post("/admin/reconciliation/{batchId}/run", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersOrderedRunHistoryOnBatchDetail() throws Exception {
        var batch = importBatch("history.csv", "CH-HISTORY");
        insertRun(batch.id(), 1, "FAILED", "first-admin", 0, 0, "database unavailable");
        insertRun(batch.id(), 2, "SUCCEEDED", "retry-admin", 0, 1, null);

        mockMvc.perform(get("/admin/reconciliation/{batchId}", batch.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("运行历史")))
                .andExpect(content().string(containsString("第 2 次")))
                .andExpect(content().string(containsString("retry-admin")))
                .andExpect(content().string(containsString("已完成")))
                .andExpect(content().string(containsString("耗时")))
                .andExpect(content().string(containsString("2 秒")))
                .andExpect(content().string(containsString("第 1 次")))
                .andExpect(content().string(containsString("first-admin")))
                .andExpect(content().string(containsString("执行失败")))
                .andExpect(content().string(containsString("database unavailable")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void importFailedBatchDetailShowsStableErrorAndSpecificReason() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "invalid-detail.csv",
                "channel_transaction_id,amount_cents,occurred_at\nCH-INVALID,nope,2026-01-15T09:30:00Z\n"
                        .getBytes(StandardCharsets.UTF_8),
                "admin"));
        assertThat(batch.status()).isEqualTo(BatchStatus.IMPORT_FAILED);
        assertThat(batch.errorMessage()).isNotBlank();

        mockMvc.perform(get("/admin/reconciliation/{batchId}", batch.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("渠道账单导入失败")))
                .andExpect(content().string(containsString(batch.errorMessage())));
    }

    @Test
    @WithMockUser(username = "retry-admin", roles = "ADMIN")
    void failedBatchShowsStableMessageAndCanRetry() throws Exception {
        var batch = importBatch("retry.csv", "CH-RETRY");
        markBatchFailed(batch.id(), "IllegalStateException: internal detail");
        insertRun(batch.id(), 1, "FAILED", "first-admin", 0, 0, "IllegalStateException: internal detail");

        mockMvc.perform(get("/admin/reconciliation/{batchId}", batch.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("对账任务失败，可重新发起")));

        mockMvc.perform(post("/admin/reconciliation/{batchId}/run", batch.id()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/" + batch.id()));
        awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);
        assertThat(reconciliationApi.findRuns(batch.id()).get(0))
                .satisfies(run -> {
                    assertThat(run.attemptNumber()).isEqualTo(2);
                    assertThat(run.requestedBy()).isEqualTo("retry-admin");
                });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void showsStableFlashWhenRunCannotStart() throws Exception {
        var batch = importBatch("invalid-start.csv", "CH-INVALID-START");
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_batch SET status = 'RUNNING' WHERE id = ?
                """, batch.id());

        mockMvc.perform(post("/admin/reconciliation/{batchId}/run", batch.id()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/" + batch.id()))
                .andExpect(result -> assertThat(result.getFlashMap().get("runError"))
                        .isEqualTo("无法启动对账，请稍后重试"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listShowsLatestRunSummary() throws Exception {
        var batch = importBatch("list-run.csv", "CH-LIST-RUN");
        insertRun(batch.id(), 1, "FAILED", "list-admin", 0, 0, "timeout");

        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("最近运行")))
                .andExpect(content().string(containsString("第 1 次")))
                .andExpect(content().string(containsString("list-admin")))
                .andExpect(content().string(containsString("执行失败")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void filtersBatchesByHistoricalRunStatusAndShowsTheMatchingAttempt() throws Exception {
        var retriedBatch = importBatch("retried-failure.csv", "CH-RETRIED-FAILURE");
        insertRun(retriedBatch.id(), 1, "FAILED", "failed-admin", 0, 1, "timeout");
        insertRun(retriedBatch.id(), 2, "SUCCEEDED", "retry-admin", 1, 0, null);
        var successfulBatch = importBatch("successful-only.csv", "CH-SUCCESSFUL-ONLY");
        insertRun(successfulBatch.id(), 1, "SUCCEEDED", "success-admin", 1, 0, null);

        mockMvc.perform(get("/admin/reconciliation").param("runStatus", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-list"))
                .andExpect(content().string(containsString("失败任务")))
                .andExpect(content().string(containsString("retried-failure.csv")))
                .andExpect(content().string(containsString("第 1 次")))
                .andExpect(content().string(containsString("failed-admin")))
                .andExpect(content().string(containsString("执行失败")))
                .andExpect(content().string(not(containsString("retry-admin"))))
                .andExpect(content().string(not(containsString("successful-only.csv"))));

        mockMvc.perform(get("/admin/reconciliation").param("runStatus", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "case-admin", roles = "ADMIN")
    void rendersChineseActiveCaseWorkbenchAndSupportsFilters() throws Exception {
        var open = createChannelOnlyCase("web-open");
        var mismatch = createAmountMismatchCase("web-mismatch", 100, 150);
        var mine = createChannelOnlyCase("web-mine");
        reconciliationApi.claim(mine.id(), "case-admin");
        var others = createChannelOnlyCase("web-others");
        reconciliationApi.claim(others.id(), "other-admin");
        var resolved = createChannelOnlyCase("web-resolved");
        reconciliationApi.claim(resolved.id(), "case-admin");
        reconciliationApi.resolve(
                resolved.id(), ResolutionCode.CHANNEL_CONFIRMED, "渠道记录正确", "case-admin");
        String openOccurredAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(open.occurredAt());

        mockMvc.perform(get("/admin/reconciliation/cases"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-cases"))
                .andExpect(content().string(containsString("异常工作台")))
                .andExpect(content().string(containsString("发生时间")))
                .andExpect(content().string(containsString(openOccurredAt)))
                .andExpect(content().string(containsString(open.channelTransactionId())))
                .andExpect(content().string(containsString(mismatch.channelTransactionId())))
                .andExpect(content().string(containsString(mine.channelTransactionId())))
                .andExpect(content().string(containsString(others.channelTransactionId())))
                .andExpect(content().string(not(containsString(resolved.channelTransactionId()))));

        mockMvc.perform(get("/admin/reconciliation/cases")
                        .param("resultType", "AMOUNT_MISMATCH"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(mismatch.channelTransactionId())))
                .andExpect(content().string(not(containsString(open.channelTransactionId()))));
        mockMvc.perform(get("/admin/reconciliation/cases")
                        .param("resolutionStatus", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(resolved.channelTransactionId())))
                .andExpect(content().string(not(containsString(open.channelTransactionId()))));
        mockMvc.perform(get("/admin/reconciliation/cases")
                        .param("assignee", "other-admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(others.channelTransactionId())))
                .andExpect(content().string(not(containsString(mine.channelTransactionId()))));
        mockMvc.perform(get("/admin/reconciliation/cases")
                        .param("onlyMine", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(mine.channelTransactionId())))
                .andExpect(content().string(not(containsString(others.channelTransactionId()))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsInvalidCaseEnumFilters() throws Exception {
        mockMvc.perform(get("/admin/reconciliation/cases")
                        .param("resultType", "NOT_A_RESULT_TYPE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/reconciliation/cases")
                        .param("resolutionStatus", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redirectsAnonymousCaseWorkbenchRequestsToLogin() throws Exception {
        mockMvc.perform(get("/admin/reconciliation/cases"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/admin/reconciliation/cases/{caseId}", UUID.randomUUID()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "case-owner", roles = "ADMIN")
    void rendersCaseDetailsAmountsOwnershipResolutionAndNewestFirstTimeline() throws Exception {
        var caseView = createAmountMismatchCase("web-detail", 100, 150);
        reconciliationApi.claim(caseView.id(), "case-owner");
        reconciliationApi.release(caseView.id(), "case-owner");
        reconciliationApi.claim(caseView.id(), "case-owner");
        reconciliationApi.resolve(
                caseView.id(), ResolutionCode.CHANNEL_CONFIRMED, "以渠道凭证为准", "case-owner");

        mockMvc.perform(get("/admin/reconciliation/cases/{caseId}", caseView.id()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-case-detail"))
                .andExpect(content().string(containsString("异常详情")))
                .andExpect(content().string(containsString("web-detail.csv")))
                .andExpect(content().string(containsString(caseView.channelTransactionId())))
                .andExpect(content().string(containsString("渠道金额（分）")))
                .andExpect(content().string(containsString(">150<")))
                .andExpect(content().string(containsString("内部金额（分）")))
                .andExpect(content().string(containsString(">100<")))
                .andExpect(content().string(containsString("差额（分）")))
                .andExpect(content().string(containsString(">-50<")))
                .andExpect(content().string(containsString("case-owner")))
                .andExpect(content().string(containsString("渠道账单为准")))
                .andExpect(content().string(containsString("以渠道凭证为准")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*data-event-action=\"RESOLVED\".*data-event-action=\"CLAIMED\""
                                + ".*data-event-action=\"RELEASED\".*data-event-action=\"CLAIMED\".*")));
    }

    @Test
    @WithMockUser(username = "case-owner", roles = "ADMIN")
    void showsActionsOnlyForAllowedCaseStateAndOwner() throws Exception {
        var open = createChannelOnlyCase("action-open");
        var mine = createChannelOnlyCase("action-mine");
        reconciliationApi.claim(mine.id(), "case-owner");
        var others = createChannelOnlyCase("action-others");
        reconciliationApi.claim(others.id(), "other-owner");
        var resolved = createChannelOnlyCase("action-resolved");
        reconciliationApi.claim(resolved.id(), "case-owner");
        reconciliationApi.resolve(resolved.id(), ResolutionCode.OTHER, "已核对", "case-owner");

        assertCaseActions(open.id(), true, false, false);
        assertCaseActions(mine.id(), false, true, true);
        assertCaseActions(others.id(), false, false, false);
        assertCaseActions(resolved.id(), false, false, false);
    }

    @Test
    @WithMockUser(username = "case-owner", roles = "ADMIN")
    void casePostActionsRequireCsrfAndUseAuthenticatedOwner() throws Exception {
        var caseView = createChannelOnlyCase("post-actions");

        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/claim", caseView.id()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/release", caseView.id()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/resolve", caseView.id())
                        .param("resolutionCode", "INTERNAL_CONFIRMED")
                        .param("note", "内部账务正确"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/claim", caseView.id()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/cases/" + caseView.id()));
        assertThat(reconciliationApi.getResult(caseView.id()).caseView().assignedTo())
                .isEqualTo("case-owner");
        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/release", caseView.id()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/claim", caseView.id()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/resolve", caseView.id())
                        .with(csrf())
                        .param("resolutionCode", "INTERNAL_CONFIRMED")
                        .param("note", "内部账务正确"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/cases/" + caseView.id()));

        assertThat(reconciliationApi.getResult(caseView.id()).caseView())
                .satisfies(resolved -> {
                    assertThat(resolved.resolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
                    assertThat(resolved.resolutionCode()).isEqualTo(ResolutionCode.INTERNAL_CONFIRMED);
                    assertThat(resolved.resolutionNote()).isEqualTo("内部账务正确");
                    assertThat(resolved.resolvedBy()).isEqualTo("case-owner");
                });
    }

    @Test
    @WithMockUser(username = "other-owner", roles = "ADMIN")
    void ownerConflictReturnsStableChineseFlash() throws Exception {
        var caseView = createChannelOnlyCase("owner-conflict");
        reconciliationApi.claim(caseView.id(), "case-owner");

        mockMvc.perform(post("/admin/reconciliation/cases/{caseId}/release", caseView.id()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/cases/" + caseView.id()))
                .andExpect(result -> assertThat(result.getFlashMap().get("caseError"))
                        .isEqualTo("操作失败，案件状态或负责人可能已变更"));
        assertThat(reconciliationApi.getResult(caseView.id()).caseView().assignedTo())
                .isEqualTo("case-owner");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersChineseResolutionCodeOptions() throws Exception {
        var caseView = createChannelOnlyCase("resolution-options");
        reconciliationApi.claim(caseView.id(), "user");

        mockMvc.perform(get("/admin/reconciliation/cases/{caseId}", caseView.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("内部账务为准")))
                .andExpect(content().string(containsString("渠道账单为准")))
                .andExpect(content().string(containsString("忽略测试数据")))
                .andExpect(content().string(containsString("其他")));
    }

    private ReconciliationBatchView importBatch(String fileName, String transactionId) {
        return reconciliationApi.importStatement(new StatementUpload(
                fileName,
                ("channel_transaction_id,amount_cents,occurred_at\n"
                        + transactionId + ",1,2026-01-15T09:30:00Z\n")
                        .getBytes(StandardCharsets.UTF_8),
                "admin"));
    }

    private ReconciliationCaseView createChannelOnlyCase(String suffix) throws InterruptedException {
        var batch = importBatch(suffix + ".csv", "CH-" + suffix.toUpperCase());
        reconciliationApi.startRun(batch.id(), "case-fixture");
        awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);
        return reconciliationApi.findCases(ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN, null).stream()
                .filter(caseView -> caseView.batchId().equals(batch.id()))
                .findFirst()
                .orElseThrow();
    }

    private ReconciliationCaseView createAmountMismatchCase(
            String suffix, long internalAmountCents, long channelAmountCents)
            throws InterruptedException {
        var account = accountsApi.create("Case " + suffix);
        var payment = paymentsApi.topUp(new TopUpCommand(
                "case-" + suffix, account.id(), internalAmountCents));
        var batch = reconciliationApi.importStatement(new StatementUpload(
                suffix + ".csv",
                ("channel_transaction_id,amount_cents,occurred_at\n"
                        + payment.channelReference() + "," + channelAmountCents + ","
                        + payment.occurredAt() + "\n").getBytes(StandardCharsets.UTF_8),
                "case-fixture"));
        reconciliationApi.startRun(batch.id(), "case-fixture");
        awaitRunStatus(batch.id(), RunStatus.SUCCEEDED);
        return reconciliationApi.findCases(ResultType.AMOUNT_MISMATCH, ResolutionStatus.OPEN, null).stream()
                .filter(caseView -> caseView.batchId().equals(batch.id()))
                .findFirst()
                .orElseThrow();
    }

    private void assertCaseActions(
            UUID caseId, boolean claimVisible, boolean releaseVisible, boolean resolveVisible)
            throws Exception {
        String body = mockMvc.perform(get("/admin/reconciliation/cases/{caseId}", caseId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body.contains("/admin/reconciliation/cases/" + caseId + "/claim"))
                .isEqualTo(claimVisible);
        assertThat(body.contains("/admin/reconciliation/cases/" + caseId + "/release"))
                .isEqualTo(releaseVisible);
        assertThat(body.contains("/admin/reconciliation/cases/" + caseId + "/resolve"))
                .isEqualTo(resolveVisible);
    }

    private void insertRun(
            UUID batchId,
            int attempt,
            String runStatus,
            String requestedBy,
            int matchedRows,
            int differenceRows,
            String errorMessage) {
        Instant requestedAt = Instant.parse("2026-01-15T10:00:00Z").plusSeconds(attempt * 60L);
        Instant startedAt = runStatus.equals("QUEUED") ? null : requestedAt.plusSeconds(1);
        Instant completedAt = runStatus.equals("QUEUED") || runStatus.equals("RUNNING")
                ? null : requestedAt.plusSeconds(3);
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at,
                     started_at, completed_at, matched_rows, difference_rows, error_message, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(), batchId, attempt, runStatus, requestedBy, Timestamp.from(requestedAt),
                startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt),
                matchedRows, differenceRows, errorMessage);
    }

    private void markBatchFailed(UUID batchId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE reconciliation.reconciliation_batch
                SET status = 'RECONCILIATION_FAILED', error_message = ?, completed_at = now()
                WHERE id = ?
                """, errorMessage, batchId);
    }

    private ReconciliationRunView awaitRunStatus(UUID batchId, RunStatus expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
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
}
