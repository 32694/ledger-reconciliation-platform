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
}
