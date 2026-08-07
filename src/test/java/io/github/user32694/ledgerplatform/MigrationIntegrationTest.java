package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class MigrationIntegrationTest {
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
    @Transactional
    void businessReferenceRemainsUniqueAfterConstraintRename() {
        var businessReference = "MIGRATION-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ledger.ledger_transaction
                    (id, business_reference, transaction_type, occurred_at)
                VALUES (?, ?, 'TOP_UP', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), businessReference);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger.ledger_transaction
                    (id, business_reference, transaction_type, occurred_at)
                VALUES (?, ?, 'TOP_UP', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), businessReference))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
