package io.github.user32694.ledgerplatform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.notifications.NotificationView;
import io.github.user32694.ledgerplatform.notifications.NotificationsApi;
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
    @Autowired ObjectMapper objectMapper;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;
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
        UUID eventId = outboxApi.append(new OutboxCommand(
                EventType.PAYMENT_SUCCEEDED,
                "PAYMENT",
                UUID.randomUUID().toString(),
                1,
                Map.of(
                        "paymentType", "TOP_UP",
                        "amountCents", 500L,
                        "channelReference", "TOPUP-RABBIT-IT"),
                Instant.now()));

        await().atMost(TEN_SECONDS).untilAsserted(() -> {
            assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
            assertThat(notificationsApi.findRecent(100))
                    .extracting(NotificationView::eventId)
                    .contains(eventId);
        });
        awaitQueuesEmpty();
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

    private int countByEventId(String table, UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE event_id = ?", Integer.class, eventId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
