package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class MigrationIntegrationTest {
    private static final String LEGACY_CONSTRAINT = "ledger_transaction_business_reference_key";
    private static final String TARGET_CONSTRAINT = "uk_ledger_transaction_business_reference";

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void appliesLegacyV2AndConstraintNamingV6() {
        var appliedVersions = jdbcTemplate.queryForList("""
                SELECT version
                FROM flyway_schema_history
                WHERE version IN ('2', '6') AND success
                ORDER BY installed_rank
                """, String.class);
        var constraintName = jdbcTemplate.queryForObject("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'ledger.ledger_transaction'::regclass
                  AND contype = 'u'
                """, String.class);

        assertThat(appliedVersions).containsExactly("2", "6");
        assertThat(constraintName).isEqualTo("uk_ledger_transaction_business_reference");
    }

    @Test
    void createsReconciliationTablesWithDatabaseConstraints() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'reconciliation'
                ORDER BY table_name
                """, String.class)).containsExactly(
                        "channel_statement_entry",
                        "reconciliation_batch",
                        "reconciliation_resolution",
                        "reconciliation_result");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_batch
                    (id, source_type, file_name, file_sha256, status, created_by, created_at)
                VALUES (?, 'OTHER', 'bad.csv', ?, 'IMPORTED', 'test', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresValidPartiesForEachPaymentType() {
        var definition = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'payments.payment_instruction'::regclass
                  AND conname = 'ck_payment_instruction_parties'
                """, String.class);

        assertThat(definition)
                .contains("payment_type")
                .contains("payer_account_id")
                .contains("payee_account_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '8' AND success
                """, Integer.class)).isOne();
    }

    @Test
    void appliesReversePaymentMigrationBeforeAuditMigration() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version
                FROM flyway_schema_history
                WHERE version IN ('9', '10') AND success
                ORDER BY installed_rank
                """, String.class)).containsExactly("9", "10");
    }

    @Test
    void widensPaymentTypeToTwentyFourCharacters() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'payments'
                  AND table_name = 'payment_instruction'
                  AND column_name = 'payment_type'
                """, Integer.class)).isEqualTo(24);
    }

    @Test
    void createsThePartialUniqueIndexForActiveReversePayments() {
        var index = jdbcTemplate.queryForMap("""
                SELECT index_definition.indisunique AS is_unique,
                       pg_get_expr(index_definition.indpred, index_definition.indrelid) AS predicate
                FROM pg_index index_definition
                JOIN pg_class index_name ON index_name.oid = index_definition.indexrelid
                WHERE index_name.relname = 'uk_payment_instruction_active_reverse'
                """);

        assertThat(index.get("is_unique")).isEqualTo(true);
        assertThat((String) index.get("predicate"))
                .contains("original_payment_id IS NOT NULL")
                .contains("PENDING")
                .contains("SUCCEEDED");
    }

    @Test
    void createsTheAuditEventTable() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'audit' AND table_name = 'audit_event'
                ORDER BY ordinal_position
                """, String.class)).containsExactly(
                        "id",
                        "actor",
                        "action",
                        "aggregate_type",
                        "aggregate_id",
                        "outcome",
                        "summary",
                        "correlation_reference",
                        "occurred_at");
    }

    @Test
    @Transactional
    void acceptsFailedReverseRetriesButRejectsAfterSucceededReverse() {
        var payeeId = insertCustomerAccount();
        var originalId = insertPayment("TOP_UP", null, payeeId, "SUCCEEDED", null, null);
        insertPayment("REFUND", null, payeeId, "FAILED", originalId, "first attempt");
        insertPayment("REFUND", null, payeeId, "FAILED", originalId, "second attempt");
        insertPayment("REFUND", null, payeeId, "SUCCEEDED", originalId, "completed refund");

        assertThatThrownBy(() -> insertPayment(
                        "REFUND", null, payeeId, "PENDING", originalId, "duplicate refund"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAnotherActiveReverseWhileOneIsPending() {
        var payeeId = insertCustomerAccount();
        var originalId = insertPayment("TOP_UP", null, payeeId, "SUCCEEDED", null, null);
        insertPayment("REFUND", null, payeeId, "PENDING", originalId, "pending refund");

        assertThatThrownBy(() -> insertPayment(
                        "REFUND", null, payeeId, "SUCCEEDED", originalId, "duplicate refund"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsReversePaymentsWithoutAValidOriginalAndReason() {
        var payeeId = insertCustomerAccount();
        var originalId = insertPayment("TOP_UP", null, payeeId, "SUCCEEDED", null, null);

        assertThatThrownBy(() -> insertPayment(
                        "REFUND", null, payeeId, "FAILED", originalId, "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsOriginalPaymentTypesWithReverseFields() {
        var payeeId = insertCustomerAccount();
        var originalId = insertPayment("TOP_UP", null, payeeId, "SUCCEEDED", null, null);

        assertThatThrownBy(() -> insertPayment(
                        "TOP_UP", null, payeeId, "SUCCEEDED", originalId, "not a reverse"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAReversePaymentThatReferencesItself() {
        var payeeId = insertCustomerAccount();
        var reverseId = UUID.randomUUID();

        assertThatThrownBy(() -> insertPayment(
                        reverseId, "REFUND", null, payeeId, "FAILED", reverseId, "self reference"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void upgradesTheLegacyConstraintWithoutRewritingExistingTransactions() throws IOException {
        jdbcTemplate.execute("ALTER TABLE ledger.ledger_transaction RENAME CONSTRAINT "
                + TARGET_CONSTRAINT + " TO " + LEGACY_CONSTRAINT);
        var businessReference = "MIGRATION-" + UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ledger.ledger_transaction
                    (id, business_reference, transaction_type, occurred_at)
                VALUES (?, ?, 'TOP_UP', CURRENT_TIMESTAMP)
                """, transactionId, businessReference);

        jdbcTemplate.execute(readV6Migration());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger.ledger_transaction WHERE id = ?", Integer.class, transactionId))
                .isEqualTo(1);
        assertThat(businessReferenceConstraintName()).isEqualTo(TARGET_CONSTRAINT);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger.ledger_transaction
                    (id, business_reference, transaction_type, occurred_at)
                VALUES (?, ?, 'TOP_UP', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), businessReference))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void acceptsAnAlreadyNamedUniqueConstraint() throws IOException {
        jdbcTemplate.execute(readV6Migration());

        assertThat(businessReferenceConstraintName()).isEqualTo(TARGET_CONSTRAINT);
    }

    @Test
    @Transactional
    void rejectsAStateWithoutTheExpectedUniqueConstraint() throws IOException {
        jdbcTemplate.execute("ALTER TABLE ledger.ledger_transaction DROP CONSTRAINT " + TARGET_CONSTRAINT);

        assertThatThrownBy(() -> jdbcTemplate.execute(readV6Migration()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Expected a unique business_reference constraint");
    }

    @Test
    @Transactional
    void rejectsTheLegacyNameOnANonUniqueConstraint() throws IOException {
        jdbcTemplate.execute("ALTER TABLE ledger.ledger_transaction DROP CONSTRAINT " + TARGET_CONSTRAINT);
        jdbcTemplate.execute("ALTER TABLE ledger.ledger_transaction ADD CONSTRAINT " + LEGACY_CONSTRAINT
                + " CHECK (business_reference IS NOT NULL)");

        assertThatThrownBy(() -> jdbcTemplate.execute(readV6Migration()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Expected a unique business_reference constraint");
    }

    private String businessReferenceConstraintName() {
        return jdbcTemplate.queryForObject("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'ledger.ledger_transaction'::regclass
                  AND contype = 'u'
                """, String.class);
    }

    private String readV6Migration() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V6__name_ledger_business_reference_constraint.sql")) {
            assertThat(input).as("V6 migration").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private UUID insertCustomerAccount() {
        var ledgerAccountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ledger.ledger_account
                    (id, owner_ref, account_type, currency, created_at)
                VALUES (?, ?, 'LIABILITY', 'CNY', CURRENT_TIMESTAMP)
                """, ledgerAccountId, "MIGRATION-" + UUID.randomUUID());
        var accountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO accounts.customer_account
                    (id, account_number, owner_name, status, currency, ledger_account_id,
                     created_at, updated_at)
                VALUES (?, ?, 'Migration Account', 'ACTIVE', 'CNY', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                accountId,
                "ACC-" + accountId.toString().replace("-", "").substring(0, 28),
                ledgerAccountId);
        return accountId;
    }

    private UUID insertPayment(
            String type,
            UUID payerId,
            UUID payeeId,
            String status,
            UUID originalPaymentId,
            String operationReason) {
        return insertPayment(
                UUID.randomUUID(),
                type,
                payerId,
                payeeId,
                status,
                originalPaymentId,
                operationReason);
    }

    private UUID insertPayment(
            UUID paymentId,
            String type,
            UUID payerId,
            UUID payeeId,
            String status,
            UUID originalPaymentId,
            String operationReason) {
        jdbcTemplate.update("""
                INSERT INTO payments.payment_instruction
                    (id, idempotency_key, request_fingerprint, channel_reference, payment_type,
                     payer_account_id, payee_account_id, amount_cents, currency, status,
                     original_payment_id, operation_reason, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 100, 'CNY', ?, ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                paymentId,
                UUID.randomUUID().toString(),
                "0".repeat(64),
                "MIGRATION-" + UUID.randomUUID(),
                type,
                payerId,
                payeeId,
                status,
                originalPaymentId,
                operationReason);
        return paymentId;
    }
}
