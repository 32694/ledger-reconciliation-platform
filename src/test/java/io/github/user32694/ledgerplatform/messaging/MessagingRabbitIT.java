package io.github.user32694.ledgerplatform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.notifications.NotificationView;
import io.github.user32694.ledgerplatform.notifications.NotificationsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"test", "messaging-integration"})
class MessagingRabbitIT {
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    @Autowired OutboxApi outboxApi;
    @Autowired NotificationsApi notificationsApi;
    @Autowired AccountsApi accountsApi;
    @Autowired PaymentsApi paymentsApi;
    @Autowired ReconciliationApi reconciliationApi;
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired CachingConnectionFactory connectionFactory;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBefore() {
        cleanState();
        listenerRegistry.start();
    }

    @AfterEach
    void cleanAfter() {
        cleanState();
    }

    @Test
    void publishesOutboxEventAndCreatesNotification() {
        UUID eventId = appendPaymentEvent();

        await().atMost(TEN_SECONDS).untilAsserted(() -> {
            assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
            assertThat(notificationsApi.findRecent(100))
                    .extracting(NotificationView::eventId)
                    .contains(eventId);
        });
        awaitQueuesEmpty();
    }

    @Test
    void retainsOutboxDuringBrokerInterruptionAndPublishesAfterRecovery() throws IOException {
        String brokerAddress = connectionFactory.getHost() + ":" + connectionFactory.getPort();
        UUID eventId;
        try {
            routeRabbitConnectionTo("127.0.0.1:" + unusedTcpPort());
            var account = accountsApi.create("Broker interruption customer " + UUID.randomUUID());
            var payment = paymentsApi.topUp(new TopUpCommand(
                    "rabbit-outage-" + UUID.randomUUID(), account.id(), 500));
            eventId = eventIdFor("PAYMENT", payment.id());

            assertThat(payment.status()).isEqualTo("SUCCEEDED");
            assertThat(paymentsApi.get(payment.id()).status()).isEqualTo("SUCCEEDED");
            await().atMost(TEN_SECONDS).untilAsserted(() -> {
                Map<String, Object> outbox = outboxState(eventId);
                assertThat(outbox).containsEntry("status", "PENDING");
                assertThat(((Number) outbox.get("attempt_count")).intValue()).isPositive();
                assertThat(countByEventId("notification.notification", eventId)).isZero();
            });
        } finally {
            routeRabbitConnectionTo(brokerAddress);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
            assertThat(notificationsApi.findRecent(100))
                    .extracting(NotificationView::eventId)
                    .contains(eventId);
        });
    }

    @Test
    void completesReconciliationAndCreatesNotification() {
        String reference = "RABBIT-RECON-" + UUID.randomUUID();
        byte[] statement = ("channel_transaction_id,amount_cents,occurred_at\n"
                        + reference + ",1,2030-01-15T09:30:00Z\n")
                .getBytes(StandardCharsets.UTF_8);
        var batch = reconciliationApi.importStatement(new StatementUpload(
                "ALIPAY", reference + ".csv", statement, "rabbit-it"));

        reconciliationApi.startRun(batch.id(), "rabbit-it");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(reconciliationApi.getBatch(batch.id()).status())
                        .isEqualTo(BatchStatus.COMPLETED));
        UUID eventId = eventIdFor("RECONCILIATION_BATCH", batch.id());
        await().atMost(TEN_SECONDS).untilAsserted(() -> {
            assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
            assertThat(notificationsApi.findRecent(100))
                    .extracting(NotificationView::eventId)
                    .contains(eventId);
        });
    }

    @Test
    void consumesDuplicateEnvelopeOnlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = paymentEnvelope(eventId);
        Message message = persistentJson(objectMapper.writeValueAsBytes(envelope), eventId.toString());

        rabbitTemplate.send(
                RabbitTopology.EVENT_EXCHANGE, EventType.PAYMENT_SUCCEEDED.routingKey(), message);
        rabbitTemplate.send(
                RabbitTopology.EVENT_EXCHANGE, EventType.PAYMENT_SUCCEEDED.routingKey(), message);

        await().atMost(TEN_SECONDS).untilAsserted(() -> {
            assertThat(countByEventId("notification.notification", eventId)).isEqualTo(1);
            assertThat(countByEventId("notification.consumed_message", eventId)).isEqualTo(1);
            assertThat(readyCount(RabbitTopology.NOTIFICATION_QUEUE)).isZero();
        });
    }

    @Test
    void retriesMalformedMessageThreeTimesBeforeDeadLettering() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long sentAt = System.nanoTime();

        rabbitTemplate.send(
                RabbitTopology.EVENT_EXCHANGE,
                EventType.PAYMENT_SUCCEEDED.routingKey(),
                persistentJson(body, UUID.randomUUID().toString()));

        await().atMost(TEN_SECONDS)
                .untilAsserted(() -> assertThat(readyCount(RabbitTopology.NOTIFICATION_DLQ))
                        .isEqualTo(1));

        assertThat(Duration.ofNanos(System.nanoTime() - sentAt))
                .isGreaterThanOrEqualTo(Duration.ofMillis(2700));
        assertThat(count("notification.notification")).isZero();
        assertThat(count("notification.consumed_message")).isZero();

        Message deadLetter = rabbitTemplate.receive(RabbitTopology.NOTIFICATION_DLQ);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(body);
        assertThat(deadLetter.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    private EventEnvelope paymentEnvelope(UUID eventId) throws Exception {
        return new EventEnvelope(
                eventId,
                EventType.PAYMENT_SUCCEEDED,
                1,
                "PAYMENT",
                UUID.randomUUID().toString(),
                Instant.now(),
                objectMapper.readTree("""
                        {
                          "paymentType": "TRANSFER",
                          "amountCents": 10000,
                          "channelReference": "TRANSFER-RABBIT-IT"
                        }
                        """));
    }

    private UUID appendPaymentEvent() {
        return outboxApi.append(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                UUID.randomUUID().toString(),
                1,
                Map.of(
                        "paymentType", "TOP_UP",
                        "amountCents", 500L,
                        "channelReference", "TOPUP-RABBIT-IT"),
                Instant.now()));
    }

    private static Message persistentJson(byte[] body, String messageId) {
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(messageId)
                .build();
    }

    private void cleanState() {
        listenerRegistry.stop();
        rabbitAdmin.purgeQueue(RabbitTopology.NOTIFICATION_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitTopology.NOTIFICATION_DLQ, false);
        awaitQueuesEmpty();
        jdbcTemplate.update("DELETE FROM notification.notification");
        jdbcTemplate.update("DELETE FROM notification.consumed_message");
        jdbcTemplate.update("DELETE FROM messaging.outbox_event");
    }

    private void awaitQueuesEmpty() {
        await().atMost(TEN_SECONDS).untilAsserted(() -> {
            assertThat(readyCount(RabbitTopology.NOTIFICATION_QUEUE)).isZero();
            assertThat(readyCount(RabbitTopology.NOTIFICATION_DLQ)).isZero();
        });
    }

    private int readyCount(String queueName) {
        QueueInformation queue = rabbitAdmin.getQueueInfo(queueName);
        assertThat(queue).as("queue %s", queueName).isNotNull();
        return queue.getMessageCount();
    }

    private String outboxStatus(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM messaging.outbox_event WHERE id = ?", String.class, eventId);
    }

    private Map<String, Object> outboxState(UUID eventId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempt_count FROM messaging.outbox_event WHERE id = ?", eventId);
    }

    private UUID eventIdFor(String aggregateType, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT id FROM messaging.outbox_event
                WHERE aggregate_type = ? AND aggregate_id = ?
                """,
                UUID.class,
                aggregateType,
                aggregateId.toString());
    }

    private int countByEventId(String table, UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE event_id = ?", Integer.class, eventId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void routeRabbitConnectionTo(String address) {
        connectionFactory.setAddresses(address);
        connectionFactory.resetConnection();
    }

    private static int unusedTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
