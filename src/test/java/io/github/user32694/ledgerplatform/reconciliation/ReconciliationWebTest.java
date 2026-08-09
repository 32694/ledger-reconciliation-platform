package io.github.user32694.ledgerplatform.reconciliation;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
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
    @WithMockUser(roles = "ADMIN")
    void runsBatchAndResolvesDifferenceThroughPostRoutes() throws Exception {
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "routes.csv", "channel_transaction_id,amount_cents,occurred_at\nCH-ROUTE,1,2026-01-15T09:30:00Z\n"
                        .getBytes(StandardCharsets.UTF_8), "admin"));

        mockMvc.perform(post("/admin/reconciliation/{batchId}/run", batch.id()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/reconciliation/*"));
        var result = reconciliationApi.findResults(batch.id(), ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN)
                .get(0);
        mockMvc.perform(post("/admin/reconciliation/results/{resultId}/resolve", result.id())
                        .with(csrf()).param("note", "已核对"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/reconciliation/*"));
    }
}
