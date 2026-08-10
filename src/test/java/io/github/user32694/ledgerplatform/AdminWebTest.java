package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditCommand;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.ReversePaymentCommand;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.payments.TransferCommand;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
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
    "DELETE FROM audit.audit_event",
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
    "TRUNCATE reconciliation.reconciliation_case_event",
    "DELETE FROM reconciliation.reconciliation_resolution",
    "DELETE FROM reconciliation.reconciliation_result",
    "DELETE FROM reconciliation.reconciliation_run",
    "DELETE FROM reconciliation.channel_statement_entry",
    "DELETE FROM reconciliation.reconciliation_batch",
    "DELETE FROM audit.audit_event",
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AdminWebTest {
    @Autowired MockMvc mockMvc;
    @Autowired AccountsApi accountsApi;
    @Autowired AuditApi auditApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID insertReconciliationOperationsSummary() {
        UUID batchId = UUID.randomUUID();
        UUID openEntryId = UUID.randomUUID();
        UUID claimedEntryId = UUID.randomUUID();
        String fileHash = (UUID.randomUUID().toString() + UUID.randomUUID())
                .replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_batch
                    (id, source_type, file_name, file_sha256, period_start, period_end,
                     status, total_rows, matched_rows, difference_rows, created_by,
                     created_at, started_at, completed_at)
                VALUES (?, 'SYNTHETIC_CHANNEL', 'overview.csv', ?,
                        '2026-01-15T09:30:00Z', '2026-01-15T10:00:00Z',
                        'COMPLETED', 2, 1, 1, 'overview-test',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, batchId, fileHash);
        jdbcTemplate.update("""
                INSERT INTO reconciliation.channel_statement_entry
                    (id, batch_id, line_number, channel_transaction_id, amount_cents, occurred_at)
                VALUES (?, ?, 2, ?, 100, '2026-01-15T09:30:00Z'),
                       (?, ?, 3, ?, 100, '2026-01-15T10:00:00Z')
                """,
                openEntryId, batchId, "OVERVIEW-OPEN-" + UUID.randomUUID(),
                claimedEntryId, batchId, "OVERVIEW-CLAIMED-" + UUID.randomUUID());
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, result_type, resolution_status, created_at)
                VALUES (?, ?, ?, 'CHANNEL_ONLY', 'OPEN', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), batchId, openEntryId);
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, result_type, resolution_status,
                     created_at, assigned_to, claimed_at)
                VALUES (?, ?, ?, 'CHANNEL_ONLY', 'CLAIMED', CURRENT_TIMESTAMP,
                        'overview-operator', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), batchId, claimedEntryId);
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at,
                     completed_at, error_message)
                VALUES (?, ?, 1, 'FAILED', 'overview-operator', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, 'overview failure')
                """, UUID.randomUUID(), batchId);
        return batchId;
    }

    @Test
    void redirectsAnonymousAdministratorToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString("管理员登录")))
                .andExpect(content().string(containsString("用户名")))
                .andExpect(content().string(containsString("密码")))
                .andExpect(content().string(containsString(">登录</button>")));

        mockMvc.perform(get("/admin/payments/transfer"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void authenticatesPersistedAdministrator() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin")
                        .param("password", "test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersOverviewForAdministrator() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/overview"))
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString("交易账本管理平台")))
                .andExpect(content().string(containsString("经营概览")))
                .andExpect(content().string(containsString("客户账户总数")))
                .andExpect(content().string(containsString("最近匹配率")))
                .andExpect(content().string(containsString("待认领异常")))
                .andExpect(content().string(containsString("处理中异常")))
                .andExpect(content().string(containsString("失败对账任务")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-match-rate\"[^>]*href=\"/admin/reconciliation\"[^>]*>.*?<strong>--</strong>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-open\"[^>]*href=\"/admin/reconciliation/cases\\?resolutionStatus=OPEN\"[^>]*>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-claimed\"[^>]*href=\"/admin/reconciliation/cases\\?resolutionStatus=CLAIMED\"[^>]*>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-failed\"[^>]*href=\"/admin/reconciliation\\?runStatus=FAILED\"[^>]*>.*")))
                .andExpect(content().string(containsString("客户账户")))
                .andExpect(content().string(containsString("资金操作")))
                .andExpect(content().string(containsString("账本流水")))
                .andExpect(content().string(containsString("自动对账")))
                .andExpect(content().string(containsString("审计日志")))
                .andExpect(content().string(not(containsString("后续开放"))))
                .andExpect(content().string(containsString("管理后台")))
                .andExpect(content().string(containsString("退出登录")))
                .andExpect(content().string(containsString("新建账户")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersReconciliationOperationsMetrics() throws Exception {
        UUID latestCompletedBatchId = insertReconciliationOperationsSummary();

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-match-rate\"[^>]*href=\"/admin/reconciliation/"
                                + latestCompletedBatchId + "\"[^>]*>.*?<strong>50\\.0%</strong>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-open\"[^>]*>.*?<strong>1</strong>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-claimed\"[^>]*>.*?<strong>1</strong>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"reconciliation-failed\"[^>]*href=\"/admin/reconciliation\\?runStatus=FAILED\"[^>]*>.*?<strong>1</strong>.*")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsAccountCreationWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/accounts").param("ownerName", "Web Customer"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createsAccountWithCsrf() throws Exception {
        mockMvc.perform(post("/admin/accounts")
                        .with(csrf())
                        .param("ownerName", "Web Customer"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersAdministrationPages() throws Exception {
        mockMvc.perform(get("/admin/accounts"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/accounts"))
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString("客户账户")))
                .andExpect(content().string(containsString("可用余额")))
                .andExpect(content().string(containsString("刷新")))
                .andExpect(content().string(containsString("新建账户")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-accounts\"[^>]*href=\"/admin/accounts\"[^>]*>.*")));
        mockMvc.perform(get("/admin/accounts/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/account-form"))
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString("新建客户账户")))
                .andExpect(content().string(containsString("新建账户")))
                .andExpect(content().string(containsString("账户名称")))
                .andExpect(content().string(containsString("创建账户")));
        mockMvc.perform(get("/admin/payments/top-up"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString("账户充值")))
                .andExpect(content().string(containsString("资金操作")))
                .andExpect(content().string(containsString("客户账户")))
                .andExpect(content().string(containsString("充值金额")))
                .andExpect(content().string(containsString("幂等键")))
                .andExpect(content().string(containsString("提交充值")))
                .andExpect(content().string(containsString("刷新")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-payments\"[^>]*href=\"/admin/payments/top-up\"[^>]*>.*")));
        mockMvc.perform(get("/admin/ledger"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/ledger"))
                .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
                .andExpect(content().string(containsString("账本流水")))
                .andExpect(content().string(containsString("业务流水号")))
                .andExpect(content().string(containsString("刷新")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-ledger\"[^>]*href=\"/admin/ledger\"[^>]*>.*")));

        mockMvc.perform(get("/admin/payments/transfer"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/transfer-form"))
                .andExpect(content().string(containsString("账户转账")))
                .andExpect(content().string(containsString("付款账户")))
                .andExpect(content().string(containsString("收款账户")))
                .andExpect(content().string(containsString("转账金额（分）")))
                .andExpect(content().string(containsString("幂等键")))
                .andExpect(content().string(containsString("提交转账")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-payments\"[^>]*"
                                + "href=\"/admin/payments/transfer\"[^>]*>.*")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsTransferWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/payments/transfer"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsBlankTransferFieldsWithChineseMessages() throws Exception {
        mockMvc.perform(post("/admin/payments/transfer")
                        .with(csrf())
                        .param("payerAccountId", "")
                        .param("payeeAccountId", "")
                        .param("amountCents", "")
                        .param("idempotencyKey", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/transfer-form"))
                .andExpect(model().attributeHasFieldErrors(
                        "transferForm",
                        "payerAccountId",
                        "payeeAccountId",
                        "amountCents",
                        "idempotencyKey"))
                .andExpect(content().string(containsString("请选择付款账户")))
                .andExpect(content().string(containsString("请选择收款账户")))
                .andExpect(content().string(containsString("请输入转账金额")))
                .andExpect(content().string(containsString("请输入幂等键")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsSelfTransferWithChineseMessage() throws Exception {
        var account = accountsApi.create("自转账客户");

        mockMvc.perform(post("/admin/payments/transfer")
                        .with(csrf())
                        .param("payerAccountId", account.id().toString())
                        .param("payeeAccountId", account.id().toString())
                        .param("amountCents", "100")
                        .param("idempotencyKey", "web-self-transfer-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/transfer-form"))
                .andExpect(model().attributeHasErrors("transferForm"))
                .andExpect(content().string(containsString("付款账户和收款账户不能相同")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsInsufficientTransferToFormWithChineseMessage() throws Exception {
        var payer = accountsApi.create("余额不足付款人");
        var payee = accountsApi.create("余额不足收款人");

        mockMvc.perform(post("/admin/payments/transfer")
                        .with(csrf())
                        .param("payerAccountId", payer.id().toString())
                        .param("payeeAccountId", payee.id().toString())
                        .param("amountCents", "100")
                        .param("idempotencyKey", "web-insufficient-transfer-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/transfer-form"))
                .andExpect(model().attributeHasErrors("transferForm"))
                .andExpect(content().string(containsString("付款账户余额不足")));

        assertThat(accountsApi.balance(payer.id()).cents()).isZero();
        assertThat(accountsApi.balance(payee.id()).cents()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void transfersFundsAndRendersChineseTransferType() throws Exception {
        var payer = accountsApi.create("网页付款人");
        var payee = accountsApi.create("网页收款人");
        paymentsApi.topUp(new TopUpCommand(
                "fund-web-transfer-" + UUID.randomUUID(), payer.id(), 1000));

        mockMvc.perform(post("/admin/payments/transfer")
                        .with(csrf())
                        .param("payerAccountId", payer.id().toString())
                        .param("payeeAccountId", payee.id().toString())
                        .param("amountCents", "400")
                        .param("idempotencyKey", "web-transfer-" + UUID.randomUUID()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/transfer"));

        assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(600);
        assertThat(accountsApi.balance(payee.id()).cents()).isEqualTo(400);
        mockMvc.perform(get("/admin/payments/transfer"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>转账</td>")))
                .andExpect(content().string(containsString("成功")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersChineseLabelsForAccountTopUpAndLedgerRows() throws Exception {
        var account = accountsApi.create("动态客户");
        paymentsApi.topUp(new TopUpCommand(
                "chinese-labels-" + UUID.randomUUID(), account.id(), 100));

        mockMvc.perform(get("/admin/accounts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("动态客户")))
                .andExpect(content().string(containsString("正常")))
                .andExpect(content().string(containsString("人民币 <span>1.00</span>")));
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>充值</td>")))
                .andExpect(content().string(containsString("成功")))
                .andExpect(content().string(containsString("人民币 <span>1.00</span>")));
        mockMvc.perform(get("/admin/payments/top-up"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("成功")))
                .andExpect(content().string(containsString("人民币 <span>1.00</span>")));
        mockMvc.perform(get("/admin/ledger"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("充值")))
                .andExpect(content().string(containsString("人民币 <span>1.00</span>")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsBlankAccountOwnerWithChineseMessage() throws Exception {
        mockMvc.perform(post("/admin/accounts")
                        .with(csrf())
                        .param("ownerName", " "))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/account-form"))
                .andExpect(model().attributeHasFieldErrors("accountForm", "ownerName"))
                .andExpect(content().string(containsString("请输入账户名称")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsInvalidAccountOwnerLengthWithChineseMessage() throws Exception {
        mockMvc.perform(post("/admin/accounts")
                        .with(csrf())
                        .param("ownerName", "甲"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/account-form"))
                .andExpect(model().attributeHasFieldErrors("accountForm", "ownerName"))
                .andExpect(content().string(containsString("账户名称需为2到100个字符")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsBlankTopUpFieldsWithChineseMessages() throws Exception {
        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", "")
                        .param("amountCents", "")
                        .param("idempotencyKey", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors(
                        "topUpForm", "accountId", "amountCents", "idempotencyKey"))
                .andExpect(content().string(containsString(
                        "<p id=\"accountId-error\" class=\"field-error\">"
                                + "请选择客户账户</p>")))
                .andExpect(content().string(containsString("请输入充值金额")))
                .andExpect(content().string(containsString("请输入幂等键")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsMalformedTopUpAccountIdWithChineseMessage() throws Exception {
        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", "not-a-uuid")
                        .param("amountCents", "100")
                        .param("idempotencyKey", "malformed-account-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors("topUpForm", "accountId"))
                .andExpect(content().string(containsString(
                        "<p id=\"accountId-error\" class=\"field-error\">"
                                + "请选择有效的客户账户</p>")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsOverflowingTopUpAmountWithChineseMessage() throws Exception {
        var account = accountsApi.create("溢出金额客户");

        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", account.id().toString())
                        .param("amountCents", "9223372036854775808")
                        .param("idempotencyKey", "overflowing-amount-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors("topUpForm", "amountCents"))
                .andExpect(content().string(containsString(
                        "<p id=\"amountCents-error\" class=\"field-error\">"
                                + "请输入充值金额</p>")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsZeroTopUpAmountWithChineseMessage() throws Exception {
        var account = accountsApi.create("零金额客户");

        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", account.id().toString())
                        .param("amountCents", "0")
                        .param("idempotencyKey", "zero-amount-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors("topUpForm", "amountCents"))
                .andExpect(content().string(containsString("充值金额必须大于0")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsLongIdempotencyKeyWithChineseMessage() throws Exception {
        var account = accountsApi.create("长幂等键客户");

        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", account.id().toString())
                        .param("amountCents", "100")
                        .param("idempotencyKey", "x".repeat(129)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors("topUpForm", "idempotencyKey"))
                .andExpect(content().string(containsString("幂等键不能超过128个字符")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsConflictingTopUpToForm() throws Exception {
        var account = accountsApi.create("Web Conflict Customer");
        String idempotencyKey = "web-conflict-" + UUID.randomUUID();
        paymentsApi.topUp(new TopUpCommand(idempotencyKey, account.id(), 100));

        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", account.id().toString())
                        .param("amountCents", "200")
                        .param("idempotencyKey", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors("topUpForm", "idempotencyKey"))
                .andExpect(content().string(containsString(
                        "该幂等键已被其他请求使用，请更换后重试")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsUnknownTopUpAccountToAccountField() throws Exception {
        mockMvc.perform(post("/admin/payments/top-up")
                        .with(csrf())
                        .param("accountId", UUID.randomUUID().toString())
                        .param("amountCents", "200")
                        .param("idempotencyKey", "unknown-account-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(model().attributeHasFieldErrors("topUpForm", "accountId"))
                .andExpect(content().string(containsString("请选择有效的客户账户")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersLargeAmountsWithoutLosingPrecision() throws Exception {
        var account = accountsApi.create("Precision Customer");
        paymentsApi.topUp(new TopUpCommand(
                "precision-" + UUID.randomUUID(), account.id(), Long.MAX_VALUE));
        String exactAmount = "92233720368547758.07";

        for (String path : new String[] {
            "/admin", "/admin/accounts", "/admin/payments/top-up", "/admin/ledger"
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("人民币")))
                    .andExpect(content().string(containsString(exactAmount)));
        }
    }

    @Test
    void protectsPaymentDetailsAndReverseRoutes() throws Exception {
        UUID paymentId = UUID.randomUUID();

        for (String path : new String[] {
            "/admin/payments/" + paymentId,
            "/admin/payments/" + paymentId + "/reverse"
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
            mockMvc.perform(post(path))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersChineseNotFoundPageForUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();

        for (String path : new String[] {
            "/admin/payments/" + paymentId,
            "/admin/payments/" + paymentId + "/reverse"
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(containsString("交易不存在")))
                    .andExpect(content().string(matchesPattern(
                            "(?s).*<a[^>]*href=\"/admin/payments/top-up\"[^>]*>资金操作</a>.*")));
        }

        mockMvc.perform(post("/admin/payments/" + paymentId + "/reverse").with(csrf())
                        .param("reason", "客户申请退款")
                        .param("idempotencyKey", "unknown-payment"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("交易不存在")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersEligiblePaymentDetailsAndLinksRecentReferences() throws Exception {
        var account = accountsApi.create("详情页客户");
        var payment = paymentsApi.topUp(new TopUpCommand(
                "web-detail-top-up-" + UUID.randomUUID(), account.id(), 500));
        String detailPath = "/admin/payments/" + payment.id();

        mockMvc.perform(get(detailPath))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-detail"))
                .andExpect(content().string(containsString("交易详情")))
                .andExpect(content().string(containsString("充值")))
                .andExpect(content().string(containsString("成功")))
                .andExpect(content().string(containsString("人民币 5.00")))
                .andExpect(content().string(containsString(account.id().toString())))
                .andExpect(content().string(containsString(payment.channelReference())))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"reverse-payment-action\"[^>]*"
                                + "href=\"" + detailPath + "/reverse\"[^>]*>发起全额退款</a>.*")));

        mockMvc.perform(get("/admin/payments/top-up"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*href=\"" + detailPath + "\"[^>]*>"
                                + payment.channelReference() + "</a>.*")));
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*href=\"" + detailPath + "\"[^>]*>"
                                + payment.channelReference() + "</a>.*")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersPendingRefundRecoveryEntryAndStoredReason() throws Exception {
        var account = accountsApi.create("退款处理中客户");
        var original = paymentsApi.topUp(new TopUpCommand(
                "web-pending-refund-source-" + UUID.randomUUID(), account.id(), 500));
        UUID pendingRefundId = insertPendingRefund(
                original.id(), account.id(), "等待退款处理");
        String reversePath = "/admin/payments/" + original.id() + "/reverse";

        mockMvc.perform(get("/admin/payments/" + original.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("全额退款处理中")))
                .andExpect(content().string(containsString("继续处理")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"resume-reverse-payment-action\"[^>]*"
                                + "href=\"" + reversePath + "\"[^>]*>继续处理</a>.*")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("已完成全额退款"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("/admin/payments/" + pendingRefundId))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("id=\"reverse-payment-action\""))));

        mockMvc.perform(get(reversePath))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(content().string(containsString("等待退款处理")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<textarea[^>]*id=\"reason\"[^>]*readonly[^>]*>等待退款处理</textarea>.*")))
                .andExpect(content().string(containsString("继续处理")));
    }

    @Test
    @WithMockUser(username = "recovery-admin", roles = "ADMIN")
    void recoversPendingRefundThroughWebExactlyOnce() throws Exception {
        var account = accountsApi.create("退款恢复客户");
        var original = paymentsApi.topUp(new TopUpCommand(
                "web-recovery-source-" + UUID.randomUUID(), account.id(), 500));
        UUID pendingRefundId = insertPendingRefund(
                original.id(), account.id(), "进程中断前原因");

        mockMvc.perform(post("/admin/payments/" + original.id() + "/reverse")
                        .with(csrf())
                        .param("reason", "页面篡改原因")
                        .param("idempotencyKey", "web-recovery-new-key"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments/" + pendingRefundId));

        var recovered = paymentsApi.get(pendingRefundId);
        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(recovered.operationReason()).isEqualTo("进程中断前原因");
        assertThat(accountsApi.balance(account.id()).cents()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ?
                """, Long.class, original.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction transaction
                JOIN payments.payment_instruction payment
                  ON payment.channel_reference = transaction.business_reference
                WHERE payment.original_payment_id = ?
                """, Long.class, original.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger.ledger_entry entry
                JOIN ledger.ledger_transaction transaction
                  ON transaction.id = entry.transaction_id
                WHERE transaction.business_reference = ?
                """, Long.class, recovered.channelReference())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit.audit_event
                WHERE action = 'PAYMENT_REFUND'
                  AND outcome = 'SUCCEEDED'
                  AND aggregate_id = ?
                """, Long.class, pendingRefundId.toString())).isOne();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void keepsPendingRefundRecoverableAfterValidationAndKeyConflict() throws Exception {
        var account = accountsApi.create("退款恢复校验客户");
        var original = paymentsApi.topUp(new TopUpCommand(
                "web-recovery-validation-source-" + UUID.randomUUID(), account.id(), 500));
        UUID pendingRefundId = insertPendingRefund(
                original.id(), account.id(), "不得丢失的原因");
        String path = "/admin/payments/" + original.id() + "/reverse";

        mockMvc.perform(post(path).with(csrf())
                        .param("reason", "不得丢失的原因")
                        .param("idempotencyKey", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(model().attributeHasFieldErrors("reverseForm", "idempotencyKey"));

        paymentsApi.topUp(new TopUpCommand(
                "web-recovery-conflicting-key", account.id(), 100));
        mockMvc.perform(post(path).with(csrf())
                        .param("reason", "页面篡改原因")
                        .param("idempotencyKey", "web-recovery-conflicting-key"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(model().attributeHasFieldErrors("reverseForm", "idempotencyKey"))
                .andExpect(content().string(containsString(
                        "该幂等键已被其他请求使用，请更换后重试")))
                .andExpect(content().string(containsString("不得丢失的原因")));

        assertThat(paymentsApi.get(pendingRefundId).status()).isEqualTo("PENDING");
        assertThat(paymentsApi.get(pendingRefundId).operationReason()).isEqualTo("不得丢失的原因");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ?
                """, Long.class, original.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, Long.class, paymentsApi.get(pendingRefundId).channelReference())).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void hidesUnknownFailureReasonBehindChineseMessage() throws Exception {
        var payer = accountsApi.create("未知失败付款人");
        var payee = accountsApi.create("未知失败收款人");
        var failed = paymentsApi.transfer(new TransferCommand(
                "web-unknown-failure-" + UUID.randomUUID(), payer.id(), payee.id(), 100));
        jdbcTemplate.update("""
                UPDATE payments.payment_instruction
                SET failure_reason = 'UNEXPECTED_GATEWAY_ERROR'
                WHERE id = ?
                """, failed.id());

        mockMvc.perform(get("/admin/payments/" + failed.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("处理失败，请查看审计日志")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("UNEXPECTED_GATEWAY_ERROR"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsReverseFormForIneligibleAndAlreadyReversedPayments() throws Exception {
        var account = accountsApi.create("不可反向客户");
        var recipient = accountsApi.create("不可反向收款人");
        var failed = paymentsApi.transfer(new TransferCommand(
                "web-failed-transfer-" + UUID.randomUUID(), account.id(), recipient.id(), 100));
        var original = paymentsApi.topUp(new TopUpCommand(
                "web-already-refunded-" + UUID.randomUUID(), account.id(), 500));
        var refund = paymentsApi.reverse(new ReversePaymentCommand(
                "web-existing-refund-" + UUID.randomUUID(), original.id(), "已完成退款"));

        for (var payment : new Object[] {failed, original, refund}) {
            var paymentView = (io.github.user32694.ledgerplatform.payments.PaymentView) payment;
            String detailPath = "/admin/payments/" + paymentView.id();
            mockMvc.perform(get(detailPath))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            containsString("id=\"reverse-payment-action\""))));
            mockMvc.perform(get(detailPath + "/reverse"))
                    .andExpect(status().isConflict())
                    .andExpect(view().name("admin/payment-detail"))
                    .andExpect(content().string(containsString("该交易不可退款或冲正")));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validatesReverseFormWithChineseMessages() throws Exception {
        var account = accountsApi.create("退款校验客户");
        var payment = paymentsApi.topUp(new TopUpCommand(
                "web-validation-source-" + UUID.randomUUID(), account.id(), 500));
        String path = "/admin/payments/" + payment.id() + "/reverse";

        mockMvc.perform(post(path).with(csrf())
                        .param("reason", "")
                        .param("idempotencyKey", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(model().attributeHasFieldErrors(
                        "reverseForm", "reason", "idempotencyKey"))
                .andExpect(content().string(containsString("请输入退款或冲正原因")))
                .andExpect(content().string(containsString("请输入幂等键")));

        mockMvc.perform(post(path).with(csrf())
                        .param("reason", "理".repeat(501))
                        .param("idempotencyKey", "k".repeat(129)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(model().attributeHasFieldErrors(
                        "reverseForm", "reason", "idempotencyKey"))
                .andExpect(content().string(containsString("退款或冲正原因不能超过500个字符")))
                .andExpect(content().string(containsString("幂等键不能超过128个字符")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void refundsSuccessfulTopUpAndShowsLinkedDetails() throws Exception {
        var account = accountsApi.create("网页退款客户");
        var original = paymentsApi.topUp(new TopUpCommand(
                "web-refund-source-" + UUID.randomUUID(), account.id(), 500));

        var result = mockMvc.perform(post("/admin/payments/" + original.id() + "/reverse")
                        .with(csrf())
                        .param("reason", "客户申请全额退款")
                        .param("idempotencyKey", "web-refund-command"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/payments/*"))
                .andReturn();
        String refundPath = result.getResponse().getRedirectedUrl();

        mockMvc.perform(get(refundPath))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("全额退款")))
                .andExpect(content().string(containsString("客户申请全额退款")))
                .andExpect(content().string(containsString("退款")))
                .andExpect(content().string(containsString(original.id().toString())));
        mockMvc.perform(get("/admin/payments/" + original.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已完成全额退款")))
                .andExpect(content().string(containsString("客户申请全额退款")))
                .andExpect(content().string(containsString(refundPath)));

        mockMvc.perform(post("/admin/payments/" + original.id() + "/reverse")
                        .with(csrf())
                        .param("reason", "不会再次执行")
                        .param("idempotencyKey", "web-refund-command-replay"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(refundPath));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reversesSuccessfulTransferAndShowsChineseCompletion() throws Exception {
        var payer = accountsApi.create("网页冲正付款人");
        var payee = accountsApi.create("网页冲正收款人");
        paymentsApi.topUp(new TopUpCommand(
                "web-reversal-funding-" + UUID.randomUUID(), payer.id(), 1000));
        var transfer = paymentsApi.transfer(new TransferCommand(
                "web-reversal-source-" + UUID.randomUUID(), payer.id(), payee.id(), 400));

        var result = mockMvc.perform(post("/admin/payments/" + transfer.id() + "/reverse")
                        .with(csrf())
                        .param("reason", "重复转账全额冲正")
                        .param("idempotencyKey", "web-reversal-command"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/payments/*"))
                .andReturn();

        mockMvc.perform(get("/admin/payments/" + transfer.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已完成全额冲正")))
                .andExpect(content().string(containsString("重复转账全额冲正")))
                .andExpect(content().string(containsString(result.getResponse().getRedirectedUrl())));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsFailedRefundAndIdempotencyConflictToReverseForm() throws Exception {
        var customer = accountsApi.create("余额不足退款客户");
        var recipient = accountsApi.create("余额不足退款收款人");
        var original = paymentsApi.topUp(new TopUpCommand(
                "web-insufficient-refund-source-" + UUID.randomUUID(), customer.id(), 500));
        paymentsApi.transfer(new TransferCommand(
                "web-spend-refund-balance-" + UUID.randomUUID(), customer.id(), recipient.id(), 500));
        String path = "/admin/payments/" + original.id() + "/reverse";

        mockMvc.perform(post(path).with(csrf())
                        .param("reason", "客户退款")
                        .param("idempotencyKey", "web-insufficient-refund"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(model().attributeHasErrors("reverseForm"))
                .andExpect(content().string(containsString(
                        "可退回余额不足，请补足资金后使用新幂等键重试")));

        var conflictSource = paymentsApi.topUp(new TopUpCommand(
                "web-reverse-conflict", recipient.id(), 100));
        mockMvc.perform(post("/admin/payments/" + conflictSource.id() + "/reverse")
                        .with(csrf())
                        .param("reason", "客户退款")
                        .param("idempotencyKey", "web-reverse-conflict"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payment-reverse-form"))
                .andExpect(model().attributeHasFieldErrors("reverseForm", "idempotencyKey"))
                .andExpect(content().string(containsString(
                        "该幂等键已被其他请求使用，请更换后重试")));
    }

    @Test
    void protectsAuditLogFromAnonymousAccess() throws Exception {
        mockMvc.perform(get("/admin/audit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "audit-admin", roles = "ADMIN")
    void filtersAuditLogAndRendersChineseLabelsForAdministrator() throws Exception {
        var account = accountsApi.create("审计日志客户");
        var topUp = paymentsApi.topUp(new TopUpCommand(
                "audit-page-top-up-" + UUID.randomUUID(), account.id(), 500));

        mockMvc.perform(get("/admin/audit")
                        .param("action", "PAYMENT_TOP_UP")
                        .param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit-list"))
                .andExpect(model().attribute("activeNav", "audit"))
                .andExpect(model().attribute("selectedAction", AuditAction.PAYMENT_TOP_UP))
                .andExpect(model().attribute("selectedOutcome", AuditOutcome.SUCCEEDED))
                .andExpect(content().string(containsString("业务审计日志")))
                .andExpect(content().string(containsString("账户充值")))
                .andExpect(content().string(containsString("成功")))
                .andExpect(content().string(containsString("账户充值成功，人民币 5.00")))
                .andExpect(content().string(not(containsString("TOP_UP CNY 500 SUCCEEDED"))))
                .andExpect(content().string(containsString("audit-admin")))
                .andExpect(content().string(containsString(topUp.id().toString())))
                .andExpect(content().string(not(containsString(account.id().toString()))))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*href=\"/admin/audit\"[^>]*class=\"[^\"]*active[^\"]*\"[^>]*>\\s*审计日志\\s*</a>.*")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersReconciliationRuleAndChannelAggregateLabels() throws Exception {
        auditApi.record(new AuditCommand(
                "rule-operator",
                AuditAction.RECONCILIATION_RULE_PUBLISH,
                "RECONCILIATION_RULE",
                "rule-label-marker",
                AuditOutcome.SUCCEEDED,
                "规则标签测试",
                null));
        auditApi.record(new AuditCommand(
                "channel-operator",
                AuditAction.RECONCILIATION_CHANNEL_STATUS_CHANGE,
                "RECONCILIATION_CHANNEL",
                "channel-label-marker",
                AuditOutcome.SUCCEEDED,
                "渠道标签测试",
                null));

        mockMvc.perform(get("/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<span>对账规则</span>\\s*"
                                + "<span class=\"mono\">rule-label-marker</span>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<span>对账渠道</span>\\s*"
                                + "<span class=\"mono\">channel-label-marker</span>.*")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void supportsAuditFilterCombinationsAndLimitsDefaultResultsToOneHundred() throws Exception {
        String successMarker = "audit-action-only-" + UUID.randomUUID();
        String failureMarker = "audit-outcome-only-" + UUID.randomUUID();
        auditApi.record(new AuditCommand(
                null,
                AuditAction.PAYMENT_REFUND,
                "PAYMENT",
                successMarker,
                AuditOutcome.SUCCEEDED,
                "人工退款备注",
                null));
        auditApi.record(new AuditCommand(
                null,
                AuditAction.PAYMENT_REVERSAL,
                "PAYMENT",
                failureMarker,
                AuditOutcome.FAILED,
                "reversal marker",
                null));

        mockMvc.perform(get("/admin/audit").param("action", "PAYMENT_REFUND"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(successMarker)))
                .andExpect(content().string(containsString("人工退款备注")))
                .andExpect(content().string(not(containsString(failureMarker))))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"PAYMENT_REFUND\"\\s+selected=\"selected\">充值退款</option>.*")))
                .andExpect(content().string(containsString("清除筛选")));

        mockMvc.perform(get("/admin/audit").param("outcome", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(failureMarker)))
                .andExpect(content().string(not(containsString(successMarker))))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<option value=\"FAILED\"\\s+selected=\"selected\">失败</option>.*")));

        mockMvc.perform(get("/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(successMarker)))
                .andExpect(content().string(containsString(failureMarker)));

        for (int index = 0; index < 101; index++) {
            String aggregateId = "audit-limit-" + index;
            auditApi.record(new AuditCommand(
                    null,
                    AuditAction.RECONCILIATION_RUN,
                    "RECONCILIATION_BATCH",
                    aggregateId,
                    AuditOutcome.SUCCEEDED,
                    "limit marker " + index,
                    null));
            jdbcTemplate.update(
                    "UPDATE audit.audit_event SET occurred_at = ? WHERE aggregate_id = ?",
                    Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index)),
                    aggregateId);
        }

        mockMvc.perform(get("/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("events", hasSize(100)))
                .andExpect(content().string(containsString("audit-limit-100")))
                .andExpect(content().string(not(containsString("audit-limit-0"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsInvalidAuditFiltersWithoutLeakingConversionDetails() throws Exception {
        for (String parameter : new String[] {"action", "outcome"}) {
            mockMvc.perform(get("/admin/audit").param(parameter, "NOT_A_VALID_ENUM"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("Whitelabel"))))
                    .andExpect(content().string(not(containsString("MethodArgumentTypeMismatchException"))))
                    .andExpect(content().string(not(containsString("Failed to convert"))));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersAuditColumnsAndNeutralPlaceholderForMissingCorrelation() throws Exception {
        String marker = "audit-columns-" + UUID.randomUUID();
        auditApi.record(new AuditCommand(
                null,
                AuditAction.RECONCILIATION_CASE_RESOLVE,
                "RECONCILIATION_DIFFERENCE",
                marker,
                AuditOutcome.SUCCEEDED,
                "已安全处理",
                null));

        mockMvc.perform(get("/admin/audit")
                        .param("action", "RECONCILIATION_CASE_RESOLVE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("时间")))
                .andExpect(content().string(containsString("操作人")))
                .andExpect(content().string(containsString("<th>操作</th>")))
                .andExpect(content().string(containsString("业务对象")))
                .andExpect(content().string(containsString("<th>结果</th>")))
                .andExpect(content().string(containsString("摘要")))
                .andExpect(content().string(containsString("关联标识")))
                .andExpect(content().string(containsString("解决差异")))
                .andExpect(content().string(containsString(marker)))
                .andExpect(content().string(containsString("已安全处理")))
                .andExpect(content().string(containsString("—")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void localizesStandardFailedPaymentSummaryWithoutLeakingReasonCode() throws Exception {
        String marker = "audit-failed-refund-" + UUID.randomUUID();
        auditApi.record(new AuditCommand(
                null,
                AuditAction.PAYMENT_REFUND,
                "PAYMENT",
                marker,
                AuditOutcome.FAILED,
                "REFUND CNY 500 FAILED INSUFFICIENT_FUNDS",
                null));

        mockMvc.perform(get("/admin/audit")
                        .param("action", "PAYMENT_REFUND")
                        .param("outcome", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "充值退款失败，人民币 5.00，余额不足")))
                .andExpect(content().string(not(containsString(
                        "REFUND CNY 500 FAILED INSUFFICIENT_FUNDS"))))
                .andExpect(content().string(not(containsString("INSUFFICIENT_FUNDS"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void preservesCanonicalLookingSuccessSummaryWithFailureToken() throws Exception {
        String marker = "audit-manual-success-with-reason-" + UUID.randomUUID();
        String summary = "REFUND CNY 500 SUCCEEDED INSUFFICIENT_FUNDS";
        auditApi.record(new AuditCommand(
                null,
                AuditAction.PAYMENT_REFUND,
                "PAYMENT",
                marker,
                AuditOutcome.SUCCEEDED,
                summary,
                null));

        mockMvc.perform(get("/admin/audit")
                        .param("action", "PAYMENT_REFUND")
                        .param("outcome", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(summary)))
                .andExpect(content().string(not(containsString("余额不足"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersRefundAndReversalLabelsInOverviewAndRecentHistory() throws Exception {
        var customer = accountsApi.create("中文反向客户");
        var recipient = accountsApi.create("中文反向收款人");
        var topUp = paymentsApi.topUp(new TopUpCommand(
                "chinese-refund-source-" + UUID.randomUUID(), customer.id(), 1000));
        var transfer = paymentsApi.transfer(new TransferCommand(
                "chinese-reversal-source-" + UUID.randomUUID(), customer.id(), recipient.id(), 400));
        paymentsApi.reverse(new ReversePaymentCommand(
                "chinese-reversal-" + UUID.randomUUID(), transfer.id(), "测试转账冲正"));
        paymentsApi.reverse(new ReversePaymentCommand(
                "chinese-refund-" + UUID.randomUUID(), topUp.id(), "测试充值退款"));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>充值退款</td>")))
                .andExpect(content().string(containsString("<td>转账冲正</td>")))
                .andExpect(content().string(not(containsString("<td>REFUND</td>"))))
                .andExpect(content().string(not(containsString("<td>REVERSAL</td>"))))
                .andExpect(content().string(containsString("最近处理的充值、转账及反向操作")));

        mockMvc.perform(get("/admin/payments/top-up"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>充值退款</td>")))
                .andExpect(content().string(containsString("<td>转账冲正</td>")))
                .andExpect(content().string(not(containsString("<td>REFUND</td>"))))
                .andExpect(content().string(not(containsString("<td>REVERSAL</td>"))))
                .andExpect(content().string(containsString("最近20条充值、转账及反向操作")));

        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
            get("/admin/ledger"), get("/admin/ledger").header("HX-Request", "true")
        }) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("<td>充值退款</td>")))
                    .andExpect(content().string(containsString("<td>转账冲正</td>")))
                    .andExpect(content().string(not(containsString("<td>REFUND</td>"))))
                    .andExpect(content().string(not(containsString("<td>REVERSAL</td>"))));
        }
    }

    private UUID insertPendingRefund(
            UUID originalPaymentId, UUID payeeAccountId, String operationReason) {
        UUID pendingRefundId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payments.payment_instruction
                    (id, idempotency_key, request_fingerprint, channel_reference, payment_type,
                     payer_account_id, payee_account_id, amount_cents, currency, status,
                     failure_reason, version, created_at, completed_at,
                     original_payment_id, operation_reason)
                VALUES (?, ?, ?, ?, 'REFUND', NULL, ?, 500, 'CNY', 'PENDING',
                        NULL, 0, ?, NULL, ?, ?)
                """,
                pendingRefundId,
                "web-pending-refund-" + pendingRefundId,
                "p".repeat(64),
                "REFUND-" + pendingRefundId,
                payeeAccountId,
                Timestamp.from(Instant.now()),
                originalPaymentId,
                operationReason);
        return pendingRefundId;
    }
}
