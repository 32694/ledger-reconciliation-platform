package io.github.user32694.ledgerplatform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void appendsOnePendingPaymentSucceededEvent() throws Exception {
        Instant occurredAt = Instant.parse("2026-08-10T01:02:03.123456Z");
        UUID eventId = outboxApi.append(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "  PAYMENT  ",
                " payment-1 ",
                1,
                Map.of("paymentType", "TOP_UP"),
                occurredAt));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT aggregate_type, aggregate_id, status, event_type, attempt_count, "
                        + "next_attempt_at, created_at, occurred_at, payload::text AS payload "
                        + "FROM messaging.outbox_event WHERE id = ?",
                eventId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messaging.outbox_event", Integer.class))
                .isEqualTo(1);
        assertThat(row.get("aggregate_type")).isEqualTo("PAYMENT");
        assertThat(row.get("aggregate_id")).isEqualTo("payment-1");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("event_type")).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("next_attempt_at")).isEqualTo(row.get("created_at"));
        assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(occurredAt);
        assertThat(objectMapper.readTree((String) row.get("payload")))
                .isEqualTo(objectMapper.readTree("{\"paymentType\":\"TOP_UP\"}"));
    }

    @Test
    void joinsAnExistingTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxApi.append(new OutboxCommand(
                    EventType.PAYMENT_SUCCEEDED,
                    "PAYMENT",
                    "rolled-back",
                    1,
                    Map.of("paymentType", "TOP_UP"),
                    Instant.parse("2026-08-10T01:02:03.123456Z")));
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messaging.outbox_event", Integer.class))
                .isZero();
    }

    @Test
    void acceptsAggregateTypeAt64CodePoints() {
        outboxApi.append(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "x".repeat(64),
                "payment-1",
                1,
                Map.of(),
                Instant.now()));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messaging.outbox_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsAggregateTypeAt65CodePoints() {
        assertRejected(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "x".repeat(65),
                "payment-1",
                1,
                Map.of(),
                Instant.now()));
    }

    @Test
    void acceptsAggregateIdAt128CodePoints() {
        outboxApi.append(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                "x".repeat(128),
                1,
                Map.of(),
                Instant.now()));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messaging.outbox_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsAggregateIdAt129CodePoints() {
        assertRejected(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                "x".repeat(129),
                1,
                Map.of(),
                Instant.now()));
    }

    @Test
    void serializesEventEnvelopeWithExactContract() throws Exception {
        UUID eventId = UUID.fromString("c0a80123-4567-489a-8abc-def012345678");
        Instant occurredAt = Instant.parse("2026-08-10T01:02:03.123456Z");
        var envelope = new EventEnvelope(
                eventId,
                EventType.PAYMENT_SUCCEEDED,
                1,
                "PAYMENT",
                "payment-1",
                occurredAt,
                objectMapper.readTree("{\"paymentType\":\"TOP_UP\"}"));

        var json = objectMapper.readTree(objectMapper.writeValueAsString(envelope));
        var fieldNames = new ArrayList<String>();
        json.fieldNames().forEachRemaining(fieldNames::add);

        assertThat(Set.copyOf(fieldNames)).containsExactlyInAnyOrder(
                "eventId",
                "eventType",
                "schemaVersion",
                "aggregateType",
                "aggregateId",
                "occurredAt",
                "payload");
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("eventType").asText()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("aggregateType").asText()).isEqualTo("PAYMENT");
        assertThat(json.get("aggregateId").asText()).isEqualTo("payment-1");
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-08-10T01:02:03.123456Z");
        assertThat(json.get("payload").get("paymentType").asText()).isEqualTo("TOP_UP");
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
