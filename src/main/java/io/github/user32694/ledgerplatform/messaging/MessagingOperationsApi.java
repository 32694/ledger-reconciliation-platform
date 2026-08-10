package io.github.user32694.ledgerplatform.messaging;

import java.util.List;
import java.util.UUID;

public interface MessagingOperationsApi {
    OutboxSummary summary();

    List<OutboxEventView> findRecent(int limit);

    QueueDepths queueDepths();

    void retryFailed(UUID eventId);
}
