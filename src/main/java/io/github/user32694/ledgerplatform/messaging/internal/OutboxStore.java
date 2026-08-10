package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.EventType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxStore {
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ERROR_CODE_POINTS = 2_000;
    private static final String UNKNOWN_ERROR = "Unknown publishing error";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    OutboxStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<ClaimedOutboxEvent> claimDue(Instant now, int limit) {
        requireNonNull(now, "Claim time");
        if (limit <= 0) {
            throw new IllegalArgumentException("Claim limit must be positive");
        }
        return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT id
                    FROM messaging.outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE messaging.outbox_event event
                SET status = 'PUBLISHING',
                    attempt_count = event.attempt_count + 1,
                    locked_at = ?
                FROM candidates
                WHERE event.id = candidates.id
                RETURNING event.id, event.aggregate_type, event.aggregate_id, event.event_type,
                          event.schema_version, event.payload::text AS payload,
                          event.attempt_count, event.occurred_at
                """,
                (resultSet, rowNumber) -> {
                    try {
                        return new ClaimedOutboxEvent(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("aggregate_type"),
                                resultSet.getString("aggregate_id"),
                                EventType.valueOf(resultSet.getString("event_type")),
                                resultSet.getInt("schema_version"),
                                objectMapper.readTree(resultSet.getString("payload")),
                                resultSet.getInt("attempt_count"),
                                resultSet.getTimestamp("occurred_at").toInstant());
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Outbox payload is not valid JSON", exception);
                    }
                },
                Timestamp.from(now),
                limit,
                Timestamp.from(now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordPublished(UUID id, Instant publishedAt) {
        requireNonNull(id, "Event id");
        requireNonNull(publishedAt, "Published time");
        int updated = jdbcTemplate.update(
                """
                UPDATE messaging.outbox_event
                SET status = 'PUBLISHED', published_at = ?, locked_at = NULL, last_error = NULL
                WHERE id = ? AND status = 'PUBLISHING'
                """,
                Timestamp.from(publishedAt),
                id);
        requireSingleUpdate(updated, id, "publish");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(UUID id, String error, Instant now) {
        requireNonNull(id, "Event id");
        requireNonNull(now, "Failure time");
        var rows = jdbcTemplate.query(
                """
                SELECT status, attempt_count
                FROM messaging.outbox_event
                WHERE id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new FailureState(
                        resultSet.getString("status"), resultSet.getInt("attempt_count")),
                id);
        if (rows.size() != 1 || !"PUBLISHING".equals(rows.get(0).status())) {
            throw new IllegalStateException("Outbox event is not being published: " + id);
        }

        int attemptCount = rows.get(0).attemptCount();
        String normalizedError = normalizeError(error);
        if (attemptCount >= MAX_ATTEMPTS) {
            jdbcTemplate.update(
                    """
                    UPDATE messaging.outbox_event
                    SET status = 'FAILED', locked_at = NULL, next_attempt_at = ?, last_error = ?
                    WHERE id = ?
                    """,
                    Timestamp.from(now),
                    normalizedError,
                    id);
            return;
        }

        int exponent = Math.max(0, Math.min(attemptCount - 1, 6));
        long delaySeconds = Math.min(1L << exponent, 60L);
        jdbcTemplate.update(
                """
                UPDATE messaging.outbox_event
                SET status = 'PENDING', locked_at = NULL, next_attempt_at = ?, last_error = ?
                WHERE id = ?
                """,
                Timestamp.from(now.plusSeconds(delaySeconds)),
                normalizedError,
                id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int recoverStale(Instant cutoff) {
        requireNonNull(cutoff, "Stale cutoff");
        return jdbcTemplate.update(
                """
                UPDATE messaging.outbox_event
                SET status = 'PENDING', locked_at = NULL
                WHERE status = 'PUBLISHING' AND locked_at < ?
                """,
                Timestamp.from(cutoff));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void retryFailed(UUID id, Instant now) {
        requireNonNull(id, "Event id");
        requireNonNull(now, "Retry time");
        int updated = jdbcTemplate.update(
                """
                UPDATE messaging.outbox_event
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?,
                    locked_at = NULL, published_at = NULL
                WHERE id = ? AND status = 'FAILED'
                """,
                Timestamp.from(now),
                id);
        requireSingleUpdate(updated, id, "retry");
    }

    private static String normalizeError(String error) {
        String normalized = error == null || error.isBlank() ? UNKNOWN_ERROR : error;
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= MAX_ERROR_CODE_POINTS) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, MAX_ERROR_CODE_POINTS));
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireSingleUpdate(int updated, UUID id, String transition) {
        if (updated != 1) {
            throw new IllegalStateException("Could not " + transition + " outbox event: " + id);
        }
    }

    private record FailureState(String status, int attemptCount) {}
}
