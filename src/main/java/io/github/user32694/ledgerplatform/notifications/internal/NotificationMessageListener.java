package io.github.user32694.ledgerplatform.notifications.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.EventEnvelope;
import io.github.user32694.ledgerplatform.messaging.RabbitTopology;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
class NotificationMessageListener {
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    NotificationMessageListener(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @RabbitListener(
            queues = RabbitTopology.NOTIFICATION_QUEUE,
            containerFactory = "notificationListenerContainerFactory")
    void receive(Message message) throws IOException {
        EventEnvelope envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
        notificationService.consume(envelope);
    }
}
