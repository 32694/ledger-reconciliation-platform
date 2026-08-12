package io.github.user32694.ledgerplatform.messaging.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.user32694.ledgerplatform.messaging.OutboxApi;
import io.github.user32694.ledgerplatform.messaging.OutboxCommand;
import io.github.user32694.ledgerplatform.messaging.MessagingOperationsApi;
import io.github.user32694.ledgerplatform.messaging.OutboxEventView;
import io.github.user32694.ledgerplatform.messaging.OutboxSummary;
import io.github.user32694.ledgerplatform.messaging.QueueDepths;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxService implements OutboxApi, MessagingOperationsApi {
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository repository;
    private final OutboxStore outboxStore;
    private final RabbitQueueProbe queueProbe;
    private final Clock clock;

    OutboxService(
            ObjectMapper objectMapper,
            OutboxEventRepository repository,
            OutboxStore outboxStore,
            RabbitQueueProbe queueProbe,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.outboxStore = outboxStore;
        this.queueProbe = queueProbe;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID append(OutboxCommand command) {
        // 业务事务调用此方法时，事件和业务事实共享同一个数据库事务。
        // 因此即使应用在提交前宕机，也不会出现“业务成功但没有消息记录”的情况。
        if (command == null) {
            throw new IllegalArgumentException("Outbox command is required");
        }
        if (command.eventType() == null) {
            throw new IllegalArgumentException("Event type is required");
        }
        String aggregateType = requireText(command.aggregateType(), "Aggregate type", 64);
        String aggregateId = requireText(command.aggregateId(), "Aggregate id", 128);
        if (command.schemaVersion() <= 0) {
            throw new IllegalArgumentException("Schema version must be greater than zero");
        }
        if (command.payload() == null) {
            throw new IllegalArgumentException("Payload is required");
        }
        if (command.occurredAt() == null) {
            throw new IllegalArgumentException("Occurred at is required");
        }

        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = new OutboxEventEntity(
                eventId,
                aggregateType,
                aggregateId,
                command.eventType(),
                command.schemaVersion(),
                objectMapper.valueToTree(command.payload()),
                OutboxStatus.PENDING,
                0,
                createdAt,
                null,
                null,
                null,
                command.occurredAt(),
                createdAt);
        repository.save(event);
        return eventId;
    }

    @Override
    @Transactional(readOnly = true)
    public OutboxSummary summary() {
        return new OutboxSummary(
                repository.countByStatus(OutboxStatus.PENDING),
                repository.countByStatus(OutboxStatus.PUBLISHING),
                repository.countByStatus(OutboxStatus.PUBLISHED),
                repository.countByStatus(OutboxStatus.FAILED));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEventView> findRecent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        return repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, limit)).stream()
                .map(OutboxEventEntity::toView)
                .toList();
    }

    @Override
    public QueueDepths queueDepths() {
        return queueProbe.queueDepths();
    }

    @Override
    public void retryFailed(UUID eventId) {
        outboxStore.retryFailed(eventId, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    private static String requireText(String value, String field, int maximumCodePoints) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maximumCodePoints + " characters");
        }
        return normalized;
    }
}
