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
import org.junit.jupiter.api.BeforeEach;
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
    private static final UUID DEFAULT_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ALIPAY_RULE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Autowired MockMvc mockMvc;
    @Autowired ReconciliationApi reconciliationApi;
    @Autowired ReconciliationRulesApi reconciliationRulesApi;
    @Autowired AccountsApi accountsApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("reconciliationTaskExecutor") ThreadPoolTaskExecutor taskExecutor;

    @BeforeEach
    void resetReconciliationRuleFixture() {
        jdbcTemplate.execute(
                "TRUNCATE reconciliation.reconciliation_rule_version, "
                        + "reconciliation.reconciliation_rule, "
                        + "reconciliation.reconciliation_channel CASCADE");
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_channel
                    (id, code, display_name, active, created_at, version)
                VALUES
                    ('00000000-0000-0000-0000-000000000001', 'ALIPAY', '支付宝', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000002', 'WECHAT_PAY', '微信支付', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000003', 'UNION_PAY', '银联', true, CURRENT_TIMESTAMP, 0),
                    ('00000000-0000-0000-0000-000000000004', 'LEGACY_SYNTHETIC', '历史兼容渠道', false, CURRENT_TIMESTAMP, 0)
                """);
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_rule
                    (id, scope_type, channel_id, version)
                VALUES
                    ('00000000-0000-0000-0000-000000000101', 'DEFAULT', NULL, 0),
                    ('00000000-0000-0000-0000-000000000102', 'CHANNEL', '00000000-0000-0000-0000-000000000001', 0),
                    ('00000000-0000-0000-0000-000000000103', 'CHANNEL', '00000000-0000-0000-0000-000000000002', 0),
                    ('00000000-0000-0000-0000-000000000104', 'CHANNEL', '00000000-0000-0000-0000-000000000003', 0)
                """);
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation.reconciliation_rule_version
                    (id, rule_id, version_number, status, amount_tolerance_cents,
                     query_window_hours, created_by, created_at, published_by, published_at)
                VALUES
                    ('00000000-0000-0000-0000-000000000201',
                     '00000000-0000-0000-0000-000000000101', 1, 'PUBLISHED', 0,
                     0, 'system', CURRENT_TIMESTAMP, 'system', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update(
                """
                UPDATE reconciliation.reconciliation_rule
                SET active_version_id = '00000000-0000-0000-0000-000000000201'
                WHERE id = '00000000-0000-0000-0000-000000000101'
                """);
    }

    @Test
    void redirectsAnonymousUserToLogin() throws Exception {
        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void redirectsAnonymousRuleManagementRequestsToLogin() throws Exception {
        mockMvc.perform(get("/admin/reconciliation/rules"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/admin/reconciliation/rules/{ruleId}/edit", DEFAULT_RULE_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ruleManagementPostsRequireCsrf() throws Exception {
        mockMvc.perform(post("/admin/reconciliation/rules/{ruleId}/draft", DEFAULT_RULE_ID)
                        .param("amountTolerance", "1.00")
                        .param("queryWindowHours", "24"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reconciliation/rules/{ruleId}/publish", DEFAULT_RULE_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reconciliation/channels/{channelCode}/status", "ALIPAY")
                        .param("active", "false"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersChineseRuleListWithDefaultAndChannelSettings() throws Exception {
        reconciliationRulesApi.saveDraft(
                ALIPAY_RULE_ID, new ReconciliationRuleDraftCommand(125, 24, "fixture-editor"));

        mockMvc.perform(get("/admin/reconciliation/rules"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-rules"))
                .andExpect(content().string(containsString("对账规则")))
                .andExpect(content().string(containsString("默认规则")))
                .andExpect(content().string(containsString("支付宝")))
                .andExpect(content().string(containsString("生效版本")))
                .andExpect(content().string(containsString("金额容差（元）")))
                .andExpect(content().string(containsString("查询窗口（小时）")))
                .andExpect(content().string(containsString("待发布草稿")))
                .andExpect(content().string(containsString("渠道状态")))
                .andExpect(content().string(containsString("编辑")))
                .andExpect(content().string(containsString("发布")))
                .andExpect(content().string(containsString("历史版本")))
                .andExpect(content().string(containsString("1.25 元")))
                .andExpect(content().string(containsString("24 小时")))
                .andExpect(content().string(containsString("启用")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editFormShowsDraftAndEffectiveValues() throws Exception {
        reconciliationRulesApi.saveDraft(
                DEFAULT_RULE_ID, new ReconciliationRuleDraftCommand(1234, 48, "fixture-editor"));

        mockMvc.perform(get("/admin/reconciliation/rules/{ruleId}/edit", DEFAULT_RULE_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-rule-edit"))
                .andExpect(content().string(containsString("编辑对账规则")))
                .andExpect(content().string(containsString("当前生效值")))
                .andExpect(content().string(containsString("0.00 元")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*name=\"amountTolerance\"[^>]*value=\"12.34\".*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*name=\"queryWindowHours\"[^>]*value=\"48\".*")));
    }

    @Test
    @WithMockUser(username = "rule-editor", roles = "ADMIN")
    void rejectsInvalidToleranceAndWindowWithActionableChineseFeedback() throws Exception {
        mockMvc.perform(post("/admin/reconciliation/rules/{ruleId}/draft", DEFAULT_RULE_ID)
                        .with(csrf())
                        .param("amountTolerance", "12.345")
                        .param("queryWindowHours", "169"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-rule-edit"))
                .andExpect(content().string(containsString("金额容差最多保留两位小数")))
                .andExpect(content().string(containsString("查询窗口必须在 0 到 168 小时之间")));

        mockMvc.perform(post("/admin/reconciliation/rules/{ruleId}/draft", DEFAULT_RULE_ID)
                        .with(csrf())
                        .param("amountTolerance", "不是数字")
                        .param("queryWindowHours", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请输入有效的金额容差")))
                .andExpect(content().string(containsString("请输入查询窗口")));
    }

    @Test
    @WithMockUser(username = "rule-editor", roles = "ADMIN")
    void savesRuleDraftWithAuthenticatedOperatorAndPrgConfirmation() throws Exception {
        mockMvc.perform(post("/admin/reconciliation/rules/{ruleId}/draft", DEFAULT_RULE_ID)
                        .with(csrf())
                        .param("amountTolerance", "1.25")
                        .param("queryWindowHours", "24")
                        .param("operator", "untrusted-user"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/rules/" + DEFAULT_RULE_ID + "/edit"))
                .andExpect(result -> assertThat(result.getFlashMap().get("ruleSuccess"))
                        .isEqualTo("对账规则草稿已保存"));

        assertThat(reconciliationRulesApi.getRule(DEFAULT_RULE_ID).draft())
                .satisfies(draft -> {
                    assertThat(draft.amountToleranceCents()).isEqualTo(125);
                    assertThat(draft.queryWindowHours()).isEqualTo(24);
                    assertThat(draft.createdBy()).isEqualTo("rule-editor");
                });
    }

    @Test
    @WithMockUser(username = "rule-publisher", roles = "ADMIN")
    void publishesExactPendingParametersAndRendersPublishedHistory() throws Exception {
        reconciliationRulesApi.saveDraft(
                ALIPAY_RULE_ID, new ReconciliationRuleDraftCommand(250, 72, "draft-editor"));

        mockMvc.perform(get("/admin/reconciliation/rules"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("待发布参数：2.50 元 / 72 小时")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*action=\"/admin/reconciliation/rules/" + ALIPAY_RULE_ID
                                + "/publish\".*name=\"_csrf\".*")));

        mockMvc.perform(post("/admin/reconciliation/rules/{ruleId}/publish", ALIPAY_RULE_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/rules"))
                .andExpect(result -> assertThat(result.getFlashMap().get("ruleSuccess"))
                        .isEqualTo("对账规则版本已发布"));

        mockMvc.perform(get("/admin/reconciliation/rules/{ruleId}/history", ALIPAY_RULE_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reconciliation-rule-history"))
                .andExpect(content().string(containsString("历史版本")))
                .andExpect(content().string(containsString("已发布")))
                .andExpect(content().string(containsString("版本 1")))
                .andExpect(content().string(containsString("2.50 元")))
                .andExpect(content().string(containsString("72 小时")))
                .andExpect(content().string(containsString("draft-editor")))
                .andExpect(content().string(containsString("rule-publisher")))
                .andExpect(content().string(containsString("发布时间")));
    }

    @Test
    @WithMockUser(username = "channel-admin", roles = "ADMIN")
    void disablesAndEnablesChannelWithAuthenticatedOperator() throws Exception {
        mockMvc.perform(post("/admin/reconciliation/channels/{channelCode}/status", "ALIPAY")
                        .with(csrf())
                        .param("active", "false")
                        .param("operator", "untrusted-user"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reconciliation/rules"))
                .andExpect(result -> assertThat(result.getFlashMap().get("ruleSuccess"))
                        .isEqualTo("支付宝已停用"));
        assertThat(reconciliationRulesApi.findChannels(true))
                .filteredOn(channel -> channel.code().equals("ALIPAY"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.active()).isFalse());

        mockMvc.perform(post("/admin/reconciliation/channels/{channelCode}/status", "ALIPAY")
                        .with(csrf())
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getFlashMap().get("ruleSuccess"))
                        .isEqualTo("支付宝已启用"));
        assertThat(reconciliationRulesApi.findChannels(true))
                .filteredOn(channel -> channel.code().equals("ALIPAY"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.active()).isTrue());
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
        var statementEntryId = jdbcTemplate.queryForObject(
                "SELECT id FROM reconciliation.channel_statement_entry WHERE batch_id = ?",
                UUID.class,
                batch.id());
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, result_type, resolution_status, created_at)
                VALUES (?, ?, ?, 'CHANNEL_ONLY', 'OPEN', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), batch.id(), statementEntryId);

        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("最近运行")))
                .andExpect(content().string(containsString("第 1 次")))
                .andExpect(content().string(containsString("list-admin")))
                .andExpect(content().string(containsString("2026-01-15T10:01:00Z")))
                .andExpect(content().string(containsString("执行失败")))
                .andExpect(content().string(containsString("异常处理")))
                .andExpect(content().string(containsString("已解决 0 / 1")))
                .andExpect(content().string(containsString("失败原因")))
                .andExpect(content().string(containsString("timeout")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersChineseResultAndResolutionFilterOptions() throws Exception {
        var batch = importBatch("localized-filters.csv", "CH-LOCALIZED-FILTERS");

        mockMvc.perform(get("/admin/reconciliation/{batchId}", batch.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"MATCHED\"[^>]*>匹配一致</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"AMOUNT_MISMATCH\"[^>]*>金额不一致</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"CHANNEL_ONLY\"[^>]*>仅渠道存在</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"INTERNAL_ONLY\"[^>]*>仅内部存在</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"NOT_REQUIRED\"[^>]*>无需处理</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"OPEN\"[^>]*>待处理</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"CLAIMED\"[^>]*>处理中</option>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"RESOLVED\"[^>]*>已解决</option>.*")));
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
