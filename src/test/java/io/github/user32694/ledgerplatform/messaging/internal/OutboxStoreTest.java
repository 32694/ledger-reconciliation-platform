package io.github.user32694.ledgerplatform.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.user32694.ledgerplatform.messaging.EventType;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql(
        statements = "DELETE FROM messaging.outbox_event",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
        statements = "DELETE FROM messaging.outbox_event",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class OutboxStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-10T07:00:00Z");
    private static final UUID FIRST_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID THIRD_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID FOURTH_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID FIFTH_ID = UUID.fromString("10000000-0000-0000-0000-000000000005");

    @Autowired OutboxStore store;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;

    @Test
    void claimsOnlyDuePendingEventsWithinTheLimit() throws Exception {
        insert(FIRST_ID, "PENDING", 0, NOW.minusSeconds(20), null, null, null, NOW.minusSeconds(30));
        insert(SECOND_ID, "PENDING", 0, NOW.minusSeconds(10), null, null, null, NOW.minusSeconds(20));
        insert(THIRD_ID, "PENDING", 0, NOW.plusSeconds(1), null, null, null, NOW.minusSeconds(10));
        insert(FOURTH_ID, "PUBLISHED", 1, NOW.minusSeconds(30), null, NOW.minusSeconds(5), null, NOW.minusSeconds(40));
        insert(FIFTH_ID, "FAILED", 10, NOW.minusSeconds(30), null, null, "failed", NOW.minusSeconds(40));

        var claimed = store.claimDue(NOW, 1);

        assertThat(claimed).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo(FIRST_ID);
            assertThat(event.aggregateType()).isEqualTo("PAYMENT");
            assertThat(event.aggregateId()).isEqualTo(FIRST_ID.toString());
            assertThat(event.eventType()).isEqualTo(EventType.PAYMENT_SUCCEEDED);
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.payload().get("paymentType").asText()).isEqualTo("TOP_UP");
            assertThat(event.attemptCount()).isEqualTo(1);
            assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
            assertThat(event.toEnvelope()).satisfies(envelope -> {
                assertThat(envelope.eventId()).isEqualTo(FIRST_ID);
                assertThat(envelope.eventType()).isEqualTo(EventType.PAYMENT_SUCCEEDED);
                assertThat(envelope.schemaVersion()).isEqualTo(1);
                assertThat(envelope.aggregateType()).isEqualTo("PAYMENT");
                assertThat(envelope.aggregateId()).isEqualTo(FIRST_ID.toString());
                assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
                assertThat(envelope.payload()).isEqualTo(event.payload());
            });
        });
        assertThat(row(FIRST_ID))
                .containsEntry("status", "PUBLISHING")
                .containsEntry("attempt_count", 1)
                .containsEntry("locked_at", Timestamp.from(NOW));
        assertThat(store.claimDue(NOW, 10))
                .extracting(ClaimedOutboxEvent::id)
                .containsExactly(SECOND_ID);
        assertThat(row(SECOND_ID))
                .containsEntry("status", "PUBLISHING")
                .containsEntry("attempt_count", 1);
        assertThat(row(THIRD_ID)).containsEntry("status", "PENDING");
        assertThat(row(FOURTH_ID)).containsEntry("status", "PUBLISHED");
        assertThat(row(FIFTH_ID)).containsEntry("status", "FAILED");
    }

    @Test
    void rejectsInvalidClaimArguments() {
        assertThatThrownBy(() -> store.claimDue(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claimDue(NOW, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claimDue(NOW, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsRowsLockedByAnotherTransaction() throws Exception {
        insert(FIRST_ID, "PENDING", 0, NOW.minusSeconds(20), null, null, null, NOW.minusSeconds(30));
        insert(SECOND_ID, "PENDING", 0, NOW.minusSeconds(10), null, null, null, NOW.minusSeconds(20));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM messaging.outbox_event WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, FIRST_ID);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }

            assertThat(store.claimDue(NOW, 2)).extracting(ClaimedOutboxEvent::id).containsExactly(SECOND_ID);
            assertThat(row(FIRST_ID)).containsEntry("status", "PENDING");
            connection.rollback();
        }
    }

    @Test
    void recordsAPublishedEventAndClearsItsLockAndError() {
        insert(FIRST_ID, "PUBLISHING", 2, NOW.minusSeconds(5), NOW, null, "old error", NOW.minusSeconds(10));
        Instant publishedAt = NOW.plusSeconds(3);

        store.recordPublished(FIRST_ID, publishedAt);

        assertThat(row(FIRST_ID))
                .containsEntry("status", "PUBLISHED")
                .containsEntry("published_at", Timestamp.from(publishedAt))
                .containsEntry("locked_at", null)
                .containsEntry("last_error", null);
    }

    @Test
    void rejectsPublishingAnEventThatIsNotBeingPublished() {
        insert(FIRST_ID, "PENDING", 0, NOW, null, null, null, NOW);

        assertThatThrownBy(() -> store.recordPublished(FIRST_ID, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(FIRST_ID)).containsEntry("status", "PENDING");
    }

    @Test
    void firstFailureSchedulesOneSecondRetry() {
        insert(FIRST_ID, "PUBLISHING", 1, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));

        store.recordFailure(FIRST_ID, "broker unavailable", NOW);

        assertThat(row(FIRST_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("next_attempt_at", Timestamp.from(NOW.plusSeconds(1)))
                .containsEntry("locked_at", null)
                .containsEntry("last_error", "broker unavailable");
    }

    @Test
    void laterFailuresGrowExponentiallyAndCapAtSixtySeconds() {
        insert(FIRST_ID, "PUBLISHING", 4, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));
        insert(SECOND_ID, "PUBLISHING", 7, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));

        store.recordFailure(FIRST_ID, "attempt four", NOW);
        store.recordFailure(SECOND_ID, "attempt seven", NOW);

        assertThat(row(FIRST_ID)).containsEntry("next_attempt_at", Timestamp.from(NOW.plusSeconds(8)));
        assertThat(row(SECOND_ID)).containsEntry("next_attempt_at", Timestamp.from(NOW.plusSeconds(60)));
    }

    @Test
    void tenthFailureBecomesFailedWithoutSchedulingAnotherRetry() {
        insert(FIRST_ID, "PUBLISHING", 10, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));

        store.recordFailure(FIRST_ID, "ten attempts", NOW);

        assertThat(row(FIRST_ID))
                .containsEntry("status", "FAILED")
                .containsEntry("attempt_count", 10)
                .containsEntry("next_attempt_at", Timestamp.from(NOW))
                .containsEntry("locked_at", null)
                .containsEntry("last_error", "ten attempts");
    }

    @Test
    void failureUsesFallbackForNullAndBlankErrors() {
        insert(FIRST_ID, "PUBLISHING", 1, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));
        insert(SECOND_ID, "PUBLISHING", 1, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));

        store.recordFailure(FIRST_ID, null, NOW);
        store.recordFailure(SECOND_ID, "  ", NOW);

        assertThat((String) row(FIRST_ID).get("last_error")).isNotBlank();
        assertThat((String) row(SECOND_ID).get("last_error")).isNotBlank();
    }

    @Test
    void failureTruncatesErrorsToTwoThousandCodePoints() {
        insert(FIRST_ID, "PUBLISHING", 1, NOW.minusSeconds(5), NOW.minusSeconds(1), null, null, NOW.minusSeconds(10));
        String error = "\uD83D\uDE00".repeat(2_001);

        store.recordFailure(FIRST_ID, error, NOW);

        String saved = (String) row(FIRST_ID).get("last_error");
        assertThat(saved.codePointCount(0, saved.length())).isEqualTo(2_000);
        assertThat(saved).isEqualTo("\uD83D\uDE00".repeat(2_000));
    }

    @Test
    void rejectsFailingAnEventThatIsNotBeingPublished() {
        insert(FIRST_ID, "PENDING", 1, NOW, null, null, null, NOW);

        assertThatThrownBy(() -> store.recordFailure(FIRST_ID, "error", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(FIRST_ID)).containsEntry("status", "PENDING");
    }

    @Test
    void recoversOnlyLocksOlderThanTheCutoffAndPreservesAttempts() {
        Instant cutoff = NOW.minusSeconds(30);
        insert(FIRST_ID, "PUBLISHING", 3, NOW.minusSeconds(60), cutoff.minusSeconds(1), null, null, NOW.minusSeconds(70));
        insert(SECOND_ID, "PUBLISHING", 4, NOW.minusSeconds(60), cutoff, null, null, NOW.minusSeconds(70));
        insert(THIRD_ID, "PENDING", 5, NOW.minusSeconds(60), cutoff.minusSeconds(1), null, null, NOW.minusSeconds(70));

        assertThat(store.recoverStale(cutoff)).isEqualTo(1);
        assertThat(row(FIRST_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 3)
                .containsEntry("next_attempt_at", Timestamp.from(NOW.minusSeconds(60)))
                .containsEntry("locked_at", null);
        assertThat(row(SECOND_ID))
                .containsEntry("status", "PUBLISHING")
                .containsEntry("attempt_count", 4)
                .containsEntry("locked_at", Timestamp.from(cutoff));
        assertThat(row(THIRD_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 5)
                .containsEntry("locked_at", Timestamp.from(cutoff.minusSeconds(1)));
    }

    @Test
    void manuallyRetriesFailedEventsAndRetainsTheLastError() {
        insert(FIRST_ID, "FAILED", 10, NOW.minusSeconds(20), NOW.minusSeconds(10), NOW.minusSeconds(5), "last failure", NOW.minusSeconds(30));

        store.retryFailed(FIRST_ID, NOW);

        assertThat(row(FIRST_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0)
                .containsEntry("next_attempt_at", Timestamp.from(NOW))
                .containsEntry("locked_at", null)
                .containsEntry("published_at", null)
                .containsEntry("last_error", "last failure");
    }

    @Test
    void rejectsManualRetryForAnEventThatIsNotFailed() {
        insert(FIRST_ID, "PENDING", 1, NOW, null, null, "last failure", NOW);

        assertThatThrownBy(() -> store.retryFailed(FIRST_ID, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(FIRST_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1);
    }

    @Test
    void rejectsNullRequiredArguments() {
        assertThatThrownBy(() -> store.recordPublished(null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.recordPublished(FIRST_ID, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.recordFailure(null, "error", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.recordFailure(FIRST_ID, "error", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.recoverStale(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.retryFailed(null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.retryFailed(FIRST_ID, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private void insert(
            UUID id,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant lockedAt,
            Instant publishedAt,
            String lastError,
            Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO messaging.outbox_event
                    (id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                     status, attempt_count, next_attempt_at, locked_at, published_at, last_error,
                     occurred_at, created_at)
                VALUES (?, 'PAYMENT', ?, 'PAYMENT_SUCCEEDED', 1, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                id.toString(),
                "{\"paymentType\":\"TOP_UP\"}",
                status,
                attemptCount,
                Timestamp.from(nextAttemptAt),
                timestamp(lockedAt),
                timestamp(publishedAt),
                lastError,
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(createdAt));
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, attempt_count, next_attempt_at, locked_at, published_at, last_error
                FROM messaging.outbox_event
                WHERE id = ?
                """,
                id);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
