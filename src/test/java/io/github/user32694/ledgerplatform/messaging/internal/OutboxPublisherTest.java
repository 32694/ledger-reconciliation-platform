package io.github.user32694.ledgerplatform.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.user32694.ledgerplatform.messaging.EventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void claimsOneAtATimeStopsWhenEmptyAndContinuesAfterPublishFailure() throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        RabbitEventPublisher gateway = mock(RabbitEventPublisher.class);
        ClaimedOutboxEvent first = event(1, 2, "2026-08-10T09:59:00Z");
        ClaimedOutboxEvent second = event(2, 3, "2026-08-10T09:58:00Z");
        ClaimedOutboxEvent third = event(3, 4, "2026-08-10T09:57:00Z");
        when(store.claimDue(NOW, 1))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of(third))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("broker unavailable")).when(gateway).publish(second);

        publisher(store, gateway, 50).publishDueEvents();

        InOrder order = inOrder(store, gateway);
        order.verify(store).recoverStale(NOW.minusSeconds(60));
        order.verify(store).claimDue(NOW, 1);
        order.verify(gateway).publish(first);
        order.verify(store).recordPublished(first.id(), first.attemptCount(), first.lockedAt(), NOW);
        order.verify(store).claimDue(NOW, 1);
        order.verify(gateway).publish(second);
        order.verify(store).recordFailure(
                second.id(), second.attemptCount(), second.lockedAt(), "broker unavailable", NOW);
        order.verify(store).claimDue(NOW, 1);
        order.verify(gateway).publish(third);
        order.verify(store).recordPublished(third.id(), third.attemptCount(), third.lockedAt(), NOW);
        order.verify(store).claimDue(NOW, 1);
        order.verifyNoMoreInteractions();
    }

    @Test
    void publishesAtMostConfiguredBatchSize() throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        RabbitEventPublisher gateway = mock(RabbitEventPublisher.class);
        AtomicInteger claims = new AtomicInteger();
        when(store.claimDue(NOW, 1)).thenAnswer(invocation ->
                List.of(event(claims.incrementAndGet(), 1, "2026-08-10T09:59:00Z")));

        publisher(store, gateway, 50).publishDueEvents();

        assertThat(claims).hasValue(50);
        verify(store, times(50)).claimDue(NOW, 1);
        verify(gateway, times(50)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void continuesWhenRecordingPublishedFailsWithoutRecordingFailure() throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        RabbitEventPublisher gateway = mock(RabbitEventPublisher.class);
        ClaimedOutboxEvent first = event(1, 2, "2026-08-10T09:59:00Z");
        ClaimedOutboxEvent second = event(2, 3, "2026-08-10T09:58:00Z");
        when(store.claimDue(NOW, 1))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("database unavailable"))
                .when(store).recordPublished(first.id(), first.attemptCount(), first.lockedAt(), NOW);

        publisher(store, gateway, 50).publishDueEvents();

        verify(gateway).publish(second);
        verify(store).recordPublished(second.id(), second.attemptCount(), second.lockedAt(), NOW);
        verify(store, never()).recordFailure(
                org.mockito.ArgumentMatchers.eq(first.id()),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void continuesWhenRecordingFailureAlsoFails() throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        RabbitEventPublisher gateway = mock(RabbitEventPublisher.class);
        ClaimedOutboxEvent first = event(1, 2, "2026-08-10T09:59:00Z");
        ClaimedOutboxEvent second = event(2, 3, "2026-08-10T09:58:00Z");
        when(store.claimDue(NOW, 1))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("broker unavailable")).when(gateway).publish(first);
        doThrow(new IllegalStateException("database unavailable")).when(store).recordFailure(
                first.id(), first.attemptCount(), first.lockedAt(), "broker unavailable", NOW);

        publisher(store, gateway, 50).publishDueEvents();

        verify(gateway).publish(second);
        verify(store).recordPublished(second.id(), second.attemptCount(), second.lockedAt(), NOW);
    }

    @Test
    void restoresInterruptRecordsFailureAndStopsThePass() throws Exception {
        OutboxStore store = mock(OutboxStore.class);
        RabbitEventPublisher gateway = mock(RabbitEventPublisher.class);
        ClaimedOutboxEvent first = event(1, 2, "2026-08-10T09:59:00Z");
        when(store.claimDue(NOW, 1))
                .thenReturn(List.of(first))
                .thenReturn(List.of(event(2, 3, "2026-08-10T09:58:00Z")));
        doThrow(new InterruptedException("publisher interrupted")).when(gateway).publish(first);
        doThrow(new IllegalStateException("database unavailable")).when(store).recordFailure(
                first.id(), first.attemptCount(), first.lockedAt(), "publisher interrupted", NOW);

        try {
            publisher(store, gateway, 50).publishDueEvents();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store, times(1)).claimDue(NOW, 1);
            verify(store).recordFailure(
                    first.id(), first.attemptCount(), first.lockedAt(), "publisher interrupted", NOW);
        } finally {
            Thread.interrupted();
        }
    }

    private static OutboxPublisher publisher(
            OutboxStore store, RabbitEventPublisher gateway, int batchSize) {
        MessagingProperties properties = new MessagingProperties();
        properties.setBatchSize(batchSize);
        properties.setStaleLockTimeout(Duration.ofSeconds(60));
        return new OutboxPublisher(store, gateway, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ClaimedOutboxEvent event(int number, int attempt, String lockedAt) {
        return new ClaimedOutboxEvent(
                UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", number)),
                "PAYMENT",
                "payment-" + number,
                EventType.PAYMENT_SUCCEEDED,
                1,
                JsonNodeFactory.instance.objectNode().put("amountCents", 100),
                attempt,
                Instant.parse(lockedAt),
                Instant.parse("2026-08-10T09:00:00Z"));
    }
}
