package io.github.user32694.ledgerplatform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
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
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AdminWebTest {
    @Autowired MockMvc mockMvc;
    @Autowired AccountsApi accountsApi;
    @Autowired PaymentsApi paymentsApi;

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
                .andExpect(content().string(containsString("客户账户")))
                .andExpect(content().string(containsString("资金操作")))
                .andExpect(content().string(containsString("账本流水")))
                .andExpect(content().string(containsString("自动对账")))
                .andExpect(content().string(containsString("审计日志")))
                .andExpect(content().string(containsString("后续开放")))
                .andExpect(content().string(containsString("管理后台")))
                .andExpect(content().string(containsString("退出登录")))
                .andExpect(content().string(containsString("新建账户")));
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
}
