package io.github.user32694.ledgerplatform.messaging.internal;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.EventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void publishesEachClaimInOrderAndRecordsLeaseAwareOutcome() throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        RabbitEventPublisher gateway = mock(RabbitEventPublisher.class);
        MessagingProperties properties = new MessagingProperties();
        properties.setBatchSize(50);
        properties.setStaleLockTimeout(java.time.Duration.ofSeconds(60));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ClaimedOutboxEvent first = event("00000000-0000-0000-0000-000000000001", 2, "2026-08-10T09:59:00Z");
        ClaimedOutboxEvent second = event("00000000-0000-0000-0000-000000000002", 3, "2026-08-10T09:58:00Z");
        ClaimedOutboxEvent third = event("00000000-0000-0000-0000-000000000003", 4, "2026-08-10T09:57:00Z");
        when(store.claimDue(NOW, 50)).thenReturn(List.of(first, second, third));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(gateway).publish(second);

        OutboxPublisher publisher = new OutboxPublisher(store, gateway, properties, clock);
        publisher.publishDueEvents();

        InOrder order = inOrder(store, gateway);
        order.verify(store).recoverStale(NOW.minusSeconds(60));
        order.verify(store).claimDue(NOW, 50);
        order.verify(gateway).publish(first);
        order.verify(store).recordPublished(first.id(), first.attemptCount(), first.lockedAt(), NOW);
        order.verify(gateway).publish(second);
        order.verify(store).recordFailure(
                second.id(), second.attemptCount(), second.lockedAt(), "broker unavailable", NOW);
        order.verify(gateway).publish(third);
        order.verify(store).recordPublished(third.id(), third.attemptCount(), third.lockedAt(), NOW);
        order.verifyNoMoreInteractions();
    }

    private static ClaimedOutboxEvent event(String id, int attempt, String lockedAt) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ClaimedOutboxEvent(
                UUID.fromString(id),
                "PAYMENT",
                "payment-" + id.substring(id.length() - 1),
                EventType.PAYMENT_SUCCEEDED,
                1,
                objectMapper.readTree("{\"amountCents\":100}"),
                attempt,
                Instant.parse(lockedAt),
                Instant.parse("2026-08-10T09:00:00Z"));
    }
}
