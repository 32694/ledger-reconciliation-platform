package io.github.user32694.ledgerplatform.notifications;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(
        UUID id,
        UUID eventId,
        String notificationType,
        String title,
        String content,
        String aggregateType,
        String aggregateId,
        Instant createdAt,
        Instant readAt) {}
