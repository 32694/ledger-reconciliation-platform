package io.github.user32694.ledgerplatform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@ApplicationModuleTest
@ActiveProfiles("test")
@Sql(
        statements = "DELETE FROM messaging.outbox_event",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
        statements = "DELETE FROM messaging.outbox_event",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class OutboxModuleTest {
    @Autowired OutboxApi outboxApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void appendsOnePendingPaymentSucceededEvent() throws Exception {
        UUID eventId = outboxApi.append(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                "payment-1",
                1,
                Map.of("paymentType", "TOP_UP"),
                Instant.parse("2026-08-10T01:02:03.123456Z")));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, event_type, payload::text AS payload FROM messaging.outbox_event WHERE id = ?",
                eventId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messaging.outbox_event", Integer.class))
                .isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("event_type")).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(objectMapper.readTree((String) row.get("payload")))
                .isEqualTo(objectMapper.readTree("{\"paymentType\":\"TOP_UP\"}"));
    }

    @Test
    void rejectsBlankAggregateTypeWithoutWritingRows() {
        assertRejected(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "  ",
                "payment-1",
                1,
                Map.of(),
                Instant.now()));
    }

    @Test
    void rejectsBlankAggregateIdWithoutWritingRows() {
        assertRejected(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                "  ",
                1,
                Map.of(),
                Instant.now()));
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void rejectsInvalidCommandFieldsWithoutWritingRows(OutboxCommand command) {
        assertRejected(command);
    }

    private static Stream<OutboxCommand> invalidCommands() {
        return Stream.of(
                null,
                new OutboxCommand(null, "PAYMENT", "payment-1", 1, Map.of(), Instant.now()),
                new OutboxCommand(EventType.PAYMENT_SUCCEEDED, null, "payment-1", 1, Map.of(), Instant.now()),
                new OutboxCommand(EventType.PAYMENT_SUCCEEDED, "PAYMENT", null, 1, Map.of(), Instant.now()),
                new OutboxCommand(EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", 0, Map.of(), Instant.now()),
                new OutboxCommand(EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", 1, null, Instant.now()),
                new OutboxCommand(EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", 1, Map.of(), null));
    }

    private void assertRejected(OutboxCommand command) {
        assertThatThrownBy(() -> outboxApi.append(command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messaging.outbox_event", Integer.class))
                .isZero();
    }
}
