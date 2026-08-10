package io.github.user32694.ledgerplatform.messaging.internal;

import io.github.user32694.ledgerplatform.messaging.QueueDepths;
import io.github.user32694.ledgerplatform.messaging.RabbitTopology;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class RabbitQueueProbe {
    private final ObjectProvider<RabbitAdmin> rabbitAdminProvider;

    RabbitQueueProbe(ObjectProvider<RabbitAdmin> rabbitAdminProvider) {
        this.rabbitAdminProvider = rabbitAdminProvider;
    }

    QueueDepths queueDepths() {
        RabbitAdmin rabbitAdmin = rabbitAdminProvider.getIfAvailable();
        if (rabbitAdmin == null) {
            return unavailable();
        }
        try {
            QueueInformation main = rabbitAdmin.getQueueInfo(RabbitTopology.NOTIFICATION_QUEUE);
            QueueInformation deadLetter = rabbitAdmin.getQueueInfo(RabbitTopology.NOTIFICATION_DLQ);
            if (main == null || deadLetter == null) {
                return unavailable();
            }
            return new QueueDepths(true, main.getMessageCount(), deadLetter.getMessageCount());
        } catch (AmqpException exception) {
            return unavailable();
        }
    }

    private static QueueDepths unavailable() {
        return new QueueDepths(false, 0, 0);
    }
}
