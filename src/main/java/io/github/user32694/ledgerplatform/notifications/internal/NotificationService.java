package io.github.user32694.ledgerplatform.notifications.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.user32694.ledgerplatform.messaging.EventEnvelope;
import io.github.user32694.ledgerplatform.notifications.NotificationView;
import io.github.user32694.ledgerplatform.notifications.NotificationsApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService implements NotificationsApi {
    private static final Map<String, String> PAYMENT_TYPE_LABELS = Map.of(
            "TOP_UP", "充值",
            "TRANSFER", "转账",
            "REFUND", "退款",
            "REVERSAL", "冲正");

    private final NotificationRepository repository;
    private final ConsumedMessageStore consumedMessageStore;

    NotificationService(
            NotificationRepository repository, ConsumedMessageStore consumedMessageStore) {
        this.repository = repository;
        this.consumedMessageStore = consumedMessageStore;
    }

    @Transactional
    void consume(EventEnvelope event) {
        ValidatedEvent validated = validate(event);
        Instant consumedAt = Instant.now();
        if (!consumedMessageStore.claim(event, consumedAt)) {
            return;
        }

        NotificationText text = format(validated);
        repository.save(new NotificationEntity(
                UUID.randomUUID(),
                event.eventId(),
                event.eventType().name(),
                text.title(),
                text.content(),
                validated.aggregateType(),
                validated.aggregateId(),
                consumedAt));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationView> findRecent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        return repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, limit)).stream()
                .map(NotificationEntity::toView)
                .toList();
    }

    @Override
    @Transactional
    public void markRead(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Notification id is required");
        }
        repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification does not exist: " + id))
                .markRead(Instant.now());
    }

    private static ValidatedEvent validate(EventEnvelope event) {
        if (event == null) {
            throw new IllegalArgumentException("Event envelope is required");
        }
        if (event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported schema version: " + event.schemaVersion());
        }
        if (event.eventId() == null) {
            throw new IllegalArgumentException("Event id is required");
        }
        if (event.eventType() == null) {
            throw new IllegalArgumentException("Event type is required");
        }
        String aggregateType = requireText(event.aggregateType(), "Aggregate type", 64);
        String aggregateId = requireText(event.aggregateId(), "Aggregate id", 128);
        if (event.occurredAt() == null) {
            throw new IllegalArgumentException("Occurred at is required");
        }
        if (event.payload() == null || !event.payload().isObject()) {
            throw new IllegalArgumentException("Payload must be an object");
        }

        return switch (event.eventType()) {
            case PAYMENT_SUCCEEDED -> new ValidatedPayment(
                    aggregateType,
                    aggregateId,
                    requireText(event.payload(), "paymentType"),
                    requireLong(event.payload(), "amountCents", false),
                    requireText(event.payload(), "channelReference"));
            case RECONCILIATION_COMPLETED -> new ValidatedReconciliation(
                    aggregateType,
                    aggregateId,
                    requireText(event.payload(), "batchId"),
                    requireText(event.payload(), "runId"),
                    requireLong(event.payload(), "matchedRows", true),
                    requireLong(event.payload(), "differenceRows", true));
        };
    }

    private static NotificationText format(ValidatedEvent event) {
        if (event instanceof ValidatedPayment payment) {
            String paymentType = PAYMENT_TYPE_LABELS.getOrDefault(
                    payment.paymentType(), payment.paymentType());
            String amount = BigDecimal.valueOf(payment.amountCents(), 2).toPlainString();
            return new NotificationText(
                    "资金操作成功",
                    "%s %s 元，渠道流水：%s"
                            .formatted(paymentType, amount, payment.channelReference()));
        }

        var reconciliation = (ValidatedReconciliation) event;
        return new NotificationText(
                "对账完成",
                "批次 %s 对账完成：匹配 %d 条，差异 %d 条；运行编号：%s"
                        .formatted(
                                reconciliation.batchId(),
                                reconciliation.matchedRows(),
                                reconciliation.differenceRows(),
                                reconciliation.runId()));
    }

    private static String requireText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return requireText(value.textValue(), field, Integer.MAX_VALUE);
    }

    private static long requireLong(JsonNode payload, String field, boolean nonNegative) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integral long");
        }
        long result = value.longValue();
        if (nonNegative && result < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return result;
    }

    private static String requireText(String value, String field, int maximumCodePoints) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maximumCodePoints + " characters");
        }
        return normalized;
    }

    private sealed interface ValidatedEvent permits ValidatedPayment, ValidatedReconciliation {
        String aggregateType();

        String aggregateId();
    }

    private record ValidatedPayment(
            String aggregateType,
            String aggregateId,
            String paymentType,
            long amountCents,
            String channelReference)
            implements ValidatedEvent {}

    private record ValidatedReconciliation(
            String aggregateType,
            String aggregateId,
            String batchId,
            String runId,
            long matchedRows,
            long differenceRows)
            implements ValidatedEvent {}

    private record NotificationText(String title, String content) {}
}
