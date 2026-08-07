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
                .andExpect(view().name("admin/overview"));
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
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-accounts\"[^>]*href=\"/admin/accounts\"[^>]*>.*")));
        mockMvc.perform(get("/admin/accounts/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/account-form"));
        mockMvc.perform(get("/admin/payments/top-up"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/topup-form"))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-payments\"[^>]*href=\"/admin/payments/top-up\"[^>]*>.*")));
        mockMvc.perform(get("/admin/ledger"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/ledger"))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a[^>]*id=\"refresh-ledger\"[^>]*href=\"/admin/ledger\"[^>]*>.*")));
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
                .andExpect(model().attributeHasFieldErrors("topUpForm", "idempotencyKey"));
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
                .andExpect(model().attributeHasFieldErrors("topUpForm", "accountId"));
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
                    .andExpect(content().string(containsString(exactAmount)));
        }
    }
}
