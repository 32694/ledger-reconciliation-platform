package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.RabbitTopology;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
class RabbitEventPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;

    RabbitEventPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    void publish(ClaimedOutboxEvent event) throws Exception {
        // 持久化消息 + publisher confirm + returned message 检查，保证至少一次投递语义。
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(event.toEnvelope());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize outbox event " + event.id(), exception);
        }

        CorrelationData correlation = new CorrelationData(event.id().toString());
        var message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.id().toString())
                .build();
        rabbitTemplate.send(
                RabbitTopology.EVENT_EXCHANGE,
                event.eventType().routingKey(),
                message,
                correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ did not confirm event " + event.id());
        }
    }
}
