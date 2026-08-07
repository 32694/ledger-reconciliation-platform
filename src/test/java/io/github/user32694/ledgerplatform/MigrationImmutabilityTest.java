package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MigrationImmutabilityTest {
    private static final String MIGRATION_ROOT = "db/migration/";

    @Test
    void v2MatchesTheFirstPublishedMigration() throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256").digest(readMigration("V2__create_ledger_tables.sql"));

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("7a7e71544f653c31df2f8ae70872d74482359c19c861d2f0890eef6208c37aba");
    }

    @Test
    void v6NamesTheBusinessReferenceConstraint() throws IOException {
        var migration = new String(readMigration("V6__name_ledger_business_reference_constraint.sql"));

        assertThat(migration)
                .contains("ledger_transaction_business_reference_key")
                .contains("uk_ledger_transaction_business_reference");
    }

    private byte[] readMigration(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(MIGRATION_ROOT + name)) {
            assertThat(input).as("migration %s", name).isNotNull();
            return input.readAllBytes();
        }
    }
}
