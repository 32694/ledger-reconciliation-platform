package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        var migration = new String(
                readMigration("V6__name_ledger_business_reference_constraint.sql"), StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ledger_transaction_business_reference_key")
                .contains("uk_ledger_transaction_business_reference")
                .contains("contype = 'u'")
                .contains("RAISE EXCEPTION");
    }

    @Test
    void v9MatchesThePublishedReversePaymentMigration()
            throws IOException, NoSuchAlgorithmException {
        assertMigrationDigest(
                "V9__add_payment_refunds_and_reversals.sql",
                "0b80a6282f7210794e57640b9bd0c8a8d946a9c73b03ecd0530d3a886d35ddfa");
    }

    @Test
    void v10MatchesThePublishedAuditMigration()
            throws IOException, NoSuchAlgorithmException {
        assertMigrationDigest(
                "V10__create_audit_events.sql",
                "dcf99cb7f678f65ca7e7780c76b2c84cd4223339dc71f6fa2ea6b8d6662b0f51");
    }

    @Test
    void v11MatchesThePublishedReverseJournalMigration()
            throws IOException, NoSuchAlgorithmException {
        assertMigrationDigest(
                "V11__allow_reverse_journal_types.sql",
                "34c99f7b048a42769ee9514d1ab663c661e57758068b02d4e905a32b8f09099f");
    }

    @Test
    void v12MatchesThePublishedReconciliationOperationsMigration()
            throws IOException, NoSuchAlgorithmException {
        assertMigrationDigest(
                "V12__add_reconciliation_operations.sql",
                "c0688282edb80c2f5d552bd316fd9112cac3c28d459fdb5fc475acb4927a7a40");
    }

    private void assertMigrationDigest(String name, String expected)
            throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256").digest(readMigration(name));
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(expected);
    }

    private byte[] readMigration(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(MIGRATION_ROOT + name)) {
            assertThat(input).as("migration %s", name).isNotNull();
            return input.readAllBytes();
        }
    }
}
