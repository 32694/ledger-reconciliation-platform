package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
        "app.admin.username=admin",
        "app.admin.password=test-password"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DELETE FROM audit.audit_event",
        "TRUNCATE reconciliation.reconciliation_case_event",
        "DELETE FROM reconciliation.reconciliation_resolution",
        "DELETE FROM reconciliation.reconciliation_result",
        "DELETE FROM reconciliation.reconciliation_result_work",
        "DELETE FROM reconciliation.reconciliation_run",
        "DELETE FROM reconciliation.channel_statement_entry",
        "DELETE FROM reconciliation.reconciliation_batch"
})
class ReconciliationWorkStoreTest {
    private static final UUID BATCH_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_STATEMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_STATEMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID PAYMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Autowired ReconciliationStore store;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AuditApi auditApi;

    @Test
    void writesWorkIdempotentlyPerRunWithoutPublishingCanonicalResults() {
        insertBatch("RUNNING", 2);
        insertStatement(FIRST_STATEMENT_ID, 2, "CHANNEL-1");
        insertRun(FIRST_RUN_ID, 1, "FAILED");
        insertRun(SECOND_RUN_ID, 2, "RUNNING");
        var result = new ReconciliationWorkResult(
                FIRST_STATEMENT_ID,
                PAYMENT_ID,
                ResultType.AMOUNT_MISMATCH,
                ResolutionStatus.OPEN);

        store.writeWorkResults(FIRST_RUN_ID, BATCH_ID, List.of(result, result));
        store.writeWorkResults(SECOND_RUN_ID, BATCH_ID, List.of(result));

        assertThat(count("reconciliation.reconciliation_result_work")).isEqualTo(2);
        assertThat(store.findResults(BATCH_ID)).isEmpty();
    }

    @Test
    void promotesOnlyTheSelectedRunAndClearsAllBatchWork() {
        insertBatch("RUNNING", 2);
        insertStatement(FIRST_STATEMENT_ID, 2, "CHANNEL-1");
        insertStatement(SECOND_STATEMENT_ID, 3, "CHANNEL-2");
        insertRun(FIRST_RUN_ID, 1, "FAILED");
        insertRun(SECOND_RUN_ID, 2, "RUNNING");
        UUID staleCanonicalId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, payment_id, result_type,
                     resolution_status, created_at)
                VALUES (?, ?, ?, NULL, 'CHANNEL_ONLY', 'OPEN', CURRENT_TIMESTAMP)
                """, staleCanonicalId, BATCH_ID, SECOND_STATEMENT_ID);
        store.writeWorkResults(FIRST_RUN_ID, BATCH_ID, List.of(new ReconciliationWorkResult(
                SECOND_STATEMENT_ID, null, ResultType.CHANNEL_ONLY, ResolutionStatus.OPEN)));
        store.writeWorkResults(SECOND_RUN_ID, BATCH_ID, List.of(new ReconciliationWorkResult(
                FIRST_STATEMENT_ID, PAYMENT_ID, ResultType.MATCHED, ResolutionStatus.NOT_REQUIRED)));
        UUID selectedWorkId = jdbcTemplate.queryForObject("""
                SELECT id FROM reconciliation.reconciliation_result_work WHERE run_id = ?
                """, UUID.class, SECOND_RUN_ID);

        store.promoteWorkResults(SECOND_RUN_ID);

        assertThat(jdbcTemplate.queryForList("""
                SELECT id FROM reconciliation.reconciliation_result WHERE batch_id = ?
                """, UUID.class, BATCH_ID)).containsExactly(selectedWorkId);
        assertThat(count("reconciliation.reconciliation_result_work")).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, matched_rows, difference_rows
                FROM reconciliation.reconciliation_batch WHERE id = ?
                """, BATCH_ID))
                .containsEntry("status", "COMPLETED")
                .containsEntry("matched_rows", 1)
                .containsEntry("difference_rows", 0);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, matched_rows, difference_rows
                FROM reconciliation.reconciliation_run WHERE id = ?
                """, SECOND_RUN_ID))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("matched_rows", 1)
                .containsEntry("difference_rows", 0);
        assertThat(auditApi.findRecent(
                        AuditAction.RECONCILIATION_RUN, AuditOutcome.SUCCEEDED, 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("operator-2");
                    assertThat(event.aggregateId()).isEqualTo(BATCH_ID.toString());
                    assertThat(event.correlationReference()).isEqualTo(SECOND_RUN_ID.toString());
                });
    }

    private void insertBatch(String status, int totalRows) {
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_batch
                    (id, source_type, file_name, file_sha256, period_start, period_end,
                     status, total_rows, created_by, created_at, started_at)
                VALUES (?, 'SYNTHETIC_CHANNEL', 'work.csv', ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, ?, 'importer', CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'RUNNING' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, BATCH_ID, "a".repeat(64), status, totalRows, status);
    }

    private void insertStatement(UUID id, int lineNumber, String reference) {
        jdbcTemplate.update("""
                INSERT INTO reconciliation.channel_statement_entry
                    (id, batch_id, line_number, channel_transaction_id, amount_cents, occurred_at)
                VALUES (?, ?, ?, ?, 100, CURRENT_TIMESTAMP)
                """, id, BATCH_ID, lineNumber, reference);
    }

    private void insertRun(UUID id, int attemptNumber, String status) {
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_run
                    (id, batch_id, attempt_number, status, requested_by, requested_at,
                     started_at, completed_at, error_message)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'FAILED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? = 'FAILED' THEN 'previous failure' ELSE NULL END)
                """, id, BATCH_ID, attemptNumber, status, "operator-" + attemptNumber, status, status);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
