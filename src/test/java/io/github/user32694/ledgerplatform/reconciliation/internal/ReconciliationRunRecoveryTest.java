package io.github.user32694.ledgerplatform.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReconciliationRunRecoveryTest {
    @Test
    void truncatesRecoveryCutoffToPostgresMicrosecondPrecision() {
        assertThat(ReconciliationRunRecovery.toDatabasePrecision(
                        Instant.parse("2026-01-15T10:00:00.123456789Z")))
                .isEqualTo(Instant.parse("2026-01-15T10:00:00.123456Z"));
    }
}
