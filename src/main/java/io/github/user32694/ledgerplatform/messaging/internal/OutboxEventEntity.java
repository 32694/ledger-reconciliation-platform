package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.user32694.ledgerplatform.messaging.EventType;
import io.github.user32694.ledgerplatform.messaging.OutboxEventView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_event", schema = "messaging")
class OutboxEventEntity {
    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private EventType eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEventEntity() {}

    OutboxEventEntity(
            UUID id,
            String aggregateType,
            String aggregateId,
            EventType eventType,
            int schemaVersion,
            JsonNode payload,
            OutboxStatus status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant lockedAt,
            Instant publishedAt,
            String lastError,
            Instant occurredAt,
            Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
        this.lockedAt = lockedAt == null ? null : lockedAt.truncatedTo(ChronoUnit.MICROS);
        this.publishedAt = publishedAt == null ? null : publishedAt.truncatedTo(ChronoUnit.MICROS);
        this.lastError = lastError;
        this.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    OutboxEventView toView() {
        return new OutboxEventView(
                id,
                aggregateType,
                aggregateId,
                eventType.name(),
                status.name(),
                attemptCount,
                nextAttemptAt,
                publishedAt,
                lastError,
                occurredAt,
                createdAt);
    }
}

enum OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
