package io.github.user32694.ledgerplatform.notifications.internal;

import io.github.user32694.ledgerplatform.notifications.NotificationView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "notification", schema = "notification")
class NotificationEntity {
    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "notification_type", nullable = false, length = 64)
    private String notificationType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationEntity() {}

    NotificationEntity(
            UUID id,
            UUID eventId,
            String notificationType,
            String title,
            String content,
            String aggregateType,
            String aggregateId,
            Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.notificationType = notificationType;
        this.title = title;
        this.content = content;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.createdAt = normalize(createdAt);
    }

    NotificationView toView() {
        return new NotificationView(
                id,
                eventId,
                notificationType,
                title,
                content,
                aggregateType,
                aggregateId,
                createdAt,
                readAt);
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
