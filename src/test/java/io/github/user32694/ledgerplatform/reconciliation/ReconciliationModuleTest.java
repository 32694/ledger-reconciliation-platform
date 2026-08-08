package io.github.user32694.ledgerplatform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
@Sql(statements = {
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
@Sql(statements = {
        "DELETE FROM reconciliation.reconciliation_resolution",
        "DELETE FROM reconciliation.reconciliation_result",
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
    @Autowired JdbcTemplate jdbcTemplate;

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

    private static byte[] csv(String rows) {
        return ("channel_transaction_id,amount_cents,occurred_at\n" + rows)
                .getBytes(StandardCharsets.UTF_8);
    }
}
