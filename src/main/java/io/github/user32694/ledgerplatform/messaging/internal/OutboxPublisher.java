package io.github.user32694.ledgerplatform.messaging.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.messaging.publisher-enabled",
        havingValue = "true",
        matchIfMissing = true)
class OutboxPublisher {
    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final RabbitEventPublisher gateway;
    private final MessagingProperties properties;
    private final Clock clock;

    OutboxPublisher(
            OutboxStore store,
            RabbitEventPublisher gateway,
            MessagingProperties properties,
            Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.messaging.publish-interval:PT1S}")
    void publishDueEvents() {
        // 先回收超时锁，再逐条 claim；逐条发布可以把 Rabbit confirm 结果准确映射回 Outbox 状态。
        Instant now = clock.instant();
        store.recoverStale(now.minus(properties.getStaleLockTimeout()));
        for (int processed = 0; processed < properties.getBatchSize(); processed++) {
            List<ClaimedOutboxEvent> claimed = store.claimDue(clock.instant(), 1);
            if (claimed.isEmpty()) {
                return;
            }
            if (!publish(claimed.get(0))) {
                return;
            }
        }
    }

    private boolean publish(ClaimedOutboxEvent event) {
        // RabbitMQ 只确认消息是否接收；数据库状态更新失败时，事件会被 stale-lock 机制重新投递。
        try {
            gateway.publish(event);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            recordFailure(event, failure);
            return false;
        } catch (Exception failure) {
            recordFailure(event, failure);
            return true;
        }

        try {
            store.recordPublished(event.id(), event.attemptCount(), event.lockedAt(), clock.instant());
        } catch (RuntimeException failure) {
            logger.error("Could not record published outbox event {}", event.id(), failure);
        }
        return true;
    }

    private void recordFailure(ClaimedOutboxEvent event, Exception publishingFailure) {
        try {
            store.recordFailure(
                    event.id(),
                    event.attemptCount(),
                    event.lockedAt(),
                    stableMessage(publishingFailure),
                    clock.instant());
        } catch (RuntimeException recordingFailure) {
            logger.error("Could not record failed outbox event {}", event.id(), recordingFailure);
        }
    }

    private static String stableMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }
}
