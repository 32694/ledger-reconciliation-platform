package io.github.user32694.ledgerplatform.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.EventEnvelope;
import io.github.user32694.ledgerplatform.messaging.EventType;
import io.github.user32694.ledgerplatform.notifications.NotificationView;
import io.github.user32694.ledgerplatform.notifications.NotificationsApi;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql(
        statements = {
            "DELETE FROM notification.notification",
            "DELETE FROM notification.consumed_message"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
        statements = {
            "DELETE FROM notification.notification",
            "DELETE FROM notification.consumed_message"
        },
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class NotificationServiceTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-10T01:02:03Z");

    @Autowired NotificationService service;
    @Autowired NotificationsApi notificationsApi;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean NotificationRepository repository;

    @Test
    void consumesAPaymentEventOnlyOnce() throws Exception {
        var envelope = paymentEnvelope(UUID.randomUUID(), "TRANSFER", 10_000, "TRANSFER-123");

        service.consume(envelope);
        service.consume(envelope);

        assertThat(notificationsApi.findRecent(100)).singleElement().satisfies(notification -> {
            assertThat(notification.eventId()).isEqualTo(envelope.eventId());
            assertThat(notification.notificationType()).isEqualTo("PAYMENT_SUCCEEDED");
            assertThat(notification.title()).isEqualTo("资金操作成功");
            assertThat(notification.content()).contains("转账").contains("100.00");
            assertThat(notification.aggregateType()).isEqualTo("PAYMENT");
            assertThat(notification.aggregateId()).isEqualTo("payment-1");
            assertThat(notification.readAt()).isNull();
        });
        assertThat(count("notification.consumed_message")).isEqualTo(1);
        assertThat(count("notification.notification")).isEqualTo(1);
    }

    @Test
    void formatsAReconciliationCompletionInChinese() throws Exception {
        UUID batchId = UUID.randomUUID();
        var envelope = new EventEnvelope(
                UUID.randomUUID(),
                EventType.RECONCILIATION_COMPLETED,
                1,
                "RECONCILIATION_BATCH",
                batchId.toString(),
                OCCURRED_AT,
                json("""
                        {
                          "batchId": "%s",
                          "runId": "%s",
                          "matchedRows": 12,
                          "differenceRows": 3,
                          "ignored": true
                        }
                        """.formatted(batchId, UUID.randomUUID())));

        service.consume(envelope);

        assertThat(notificationsApi.findRecent(100)).singleElement().satisfies(notification -> {
            assertThat(notification.notificationType()).isEqualTo("RECONCILIATION_COMPLETED");
            assertThat(notification.title()).isEqualTo("对账完成");
            assertThat(notification.content()).contains("匹配 12 条").contains("差异 3 条");
        });
    }

    @Test
    void rejectsUnsupportedSchemaBeforeClaiming() throws Exception {
        var valid = paymentEnvelope(UUID.randomUUID(), "TOP_UP", 100, "TOPUP-1");
        var unsupported = new EventEnvelope(
                valid.eventId(),
                valid.eventType(),
                2,
                valid.aggregateType(),
                valid.aggregateId(),
                valid.occurredAt(),
                valid.payload());

        assertThatThrownBy(() -> service.consume(unsupported))
                .isInstanceOf(IllegalArgumentException.class);

        assertTablesEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEnvelopes")
    void rejectsMissingOrWrongTypedFieldsWithoutClaiming(
            String description, EventEnvelope envelope) {
        assertThatThrownBy(() -> service.consume(envelope))
                .isInstanceOf(IllegalArgumentException.class);

        assertTablesEmpty();
    }

    @Test
    void rollsBackTheClaimWhenSavingTheNotificationFails() throws Exception {
        var envelope = paymentEnvelope(UUID.randomUUID(), "TOP_UP", 100, "TOPUP-1");
        doThrow(new DataIntegrityViolationException("simulated save failure"))
                .when(repository)
                .save(any(NotificationEntity.class));

        assertThatThrownBy(() -> service.consume(envelope))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertTablesEmpty();
    }

    @Test
    void findsRecentNotificationsByCreatedAtThenIdAndValidatesLimit() {
        UUID first = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID latest = UUID.fromString("10000000-0000-0000-0000-000000000003");
        insertNotification(first, Instant.parse("2026-08-10T01:00:00Z"));
        insertNotification(second, Instant.parse("2026-08-10T01:00:00Z"));
        insertNotification(latest, Instant.parse("2026-08-10T02:00:00Z"));

        assertThat(notificationsApi.findRecent(2))
                .extracting(NotificationView::id)
                .containsExactly(latest, second);
        assertThatThrownBy(() -> notificationsApi.findRecent(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notificationsApi.findRecent(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marksAnExistingNotificationReadOnlyOnce() throws Exception {
        service.consume(paymentEnvelope(UUID.randomUUID(), "TOP_UP", 100, "TOPUP-1"));
        UUID notificationId = notificationsApi.findRecent(1).get(0).id();

        notificationsApi.markRead(notificationId);
        Instant firstReadAt = notificationsApi.findRecent(1).get(0).readAt();
        notificationsApi.markRead(notificationId);

        assertThat(firstReadAt).isNotNull();
        assertThat(notificationsApi.findRecent(1).get(0).readAt()).isEqualTo(firstReadAt);
        assertThatThrownBy(() -> notificationsApi.markRead(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notificationsApi.markRead(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidEnvelopes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UUID eventId = UUID.randomUUID();
        return Stream.of(
                Arguments.of("null envelope", null),
                Arguments.of(
                        "missing event id",
                        envelope(null, EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", mapper.readTree("""
                                {"paymentType":"TOP_UP","amountCents":100,"channelReference":"TOPUP-1"}
                                """))),
                Arguments.of(
                        "blank aggregate type",
                        envelope(eventId, EventType.PAYMENT_SUCCEEDED, " ", "payment-1", mapper.readTree("""
                                {"paymentType":"TOP_UP","amountCents":100,"channelReference":"TOPUP-1"}
                                """))),
                Arguments.of(
                        "missing payment type",
                        envelope(eventId, EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", mapper.readTree("""
                                {"amountCents":100,"channelReference":"TOPUP-1"}
                                """))),
                Arguments.of(
                        "fractional payment amount",
                        envelope(eventId, EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", mapper.readTree("""
                                {"paymentType":"TOP_UP","amountCents":1.5,"channelReference":"TOPUP-1"}
                                """))),
                Arguments.of(
                        "non-string channel reference",
                        envelope(eventId, EventType.PAYMENT_SUCCEEDED, "PAYMENT", "payment-1", mapper.readTree("""
                                {"paymentType":"TOP_UP","amountCents":100,"channelReference":123}
                                """))),
                Arguments.of(
                        "negative matched rows",
                        envelope(eventId, EventType.RECONCILIATION_COMPLETED, "RECONCILIATION_BATCH", "batch-1", mapper.readTree("""
                                {"batchId":"batch-1","runId":"run-1","matchedRows":-1,"differenceRows":0}
                                """))),
                Arguments.of(
                        "text difference rows",
                        envelope(eventId, EventType.RECONCILIATION_COMPLETED, "RECONCILIATION_BATCH", "batch-1", mapper.readTree("""
                                {"batchId":"batch-1","runId":"run-1","matchedRows":1,"differenceRows":"0"}
                                """))));
    }

    private EventEnvelope paymentEnvelope(
            UUID eventId, String paymentType, long amountCents, String channelReference)
            throws Exception {
        return envelope(
                eventId,
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                "payment-1",
                json("""
                        {
                          "paymentType": "%s",
                          "amountCents": %d,
                          "channelReference": "%s"
                        }
                        """.formatted(paymentType, amountCents, channelReference)));
    }

    private static EventEnvelope envelope(
            UUID eventId,
            EventType eventType,
            String aggregateType,
            String aggregateId,
            JsonNode payload) {
        return new EventEnvelope(
                eventId, eventType, 1, aggregateType, aggregateId, OCCURRED_AT, payload);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertTablesEmpty() {
        assertThat(count("notification.consumed_message")).isZero();
        assertThat(count("notification.notification")).isZero();
    }

    private void insertNotification(UUID id, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO notification.notification
                    (id, event_id, notification_type, title, content,
                     aggregate_type, aggregate_id, created_at)
                VALUES (?, ?, 'PAYMENT_SUCCEEDED', '资金操作成功', '充值 1.00',
                        'PAYMENT', 'payment-1', ?)
                """, id, UUID.randomUUID(), Timestamp.from(createdAt));
    }
}
