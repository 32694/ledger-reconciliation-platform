package io.github.user32694.ledgerplatform.messaging.internal;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.messaging.publisher-enabled",
        havingValue = "true",
        matchIfMissing = true)
class OutboxPublisher {
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
        Instant now = clock.instant();
        store.recoverStale(now.minus(properties.getStaleLockTimeout()));
        for (ClaimedOutboxEvent event : store.claimDue(now, properties.getBatchSize())) {
            try {
                gateway.publish(event);
            } catch (Exception failure) {
                store.recordFailure(
                        event.id(),
                        event.attemptCount(),
                        event.lockedAt(),
                        stableMessage(failure),
                        clock.instant());
                continue;
            }
            store.recordPublished(event.id(), event.attemptCount(), event.lockedAt(), clock.instant());
        }
    }

    private static String stableMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }
}
