package io.github.user32694.ledgerplatform.audit.internal;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditCommand;
import io.github.user32694.ledgerplatform.audit.AuditEventView;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditService implements AuditApi {
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final AuditEventRepository repository;

    AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public AuditEventView record(AuditCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Audit command is required");
        }
        if (command.action() == null) {
            throw new IllegalArgumentException("Action is required");
        }
        if (command.outcome() == null) {
            throw new IllegalArgumentException("Outcome is required");
        }

        String actor = resolveActor(command.actor());
        String aggregateType = requireText(command.aggregateType(), "Aggregate type", 64);
        String aggregateId = requireText(command.aggregateId(), "Aggregate id", 128);
        String summary = requireText(command.summary(), "Summary", 500);
        String correlationReference = optionalText(
                command.correlationReference(), "Correlation reference", 128);

        var event = new AuditEventEntity(
                UUID.randomUUID(),
                actor,
                command.action(),
                aggregateType,
                aggregateId,
                command.outcome(),
                summary,
                correlationReference,
                Instant.now());
        return repository.save(event).toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEventView> findRecent(
            AuditAction action, AuditOutcome outcome, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        var page = PageRequest.of(0, limit);
        List<AuditEventEntity> events;
        if (action != null && outcome != null) {
            events = repository.findAllByActionAndOutcomeOrderByOccurredAtDescIdDesc(
                    action, outcome, page);
        } else if (action != null) {
            events = repository.findAllByActionOrderByOccurredAtDescIdDesc(action, page);
        } else if (outcome != null) {
            events = repository.findAllByOutcomeOrderByOccurredAtDescIdDesc(outcome, page);
        } else {
            events = repository.findAllByOrderByOccurredAtDescIdDesc(page);
        }
        return events.stream().map(AuditEventEntity::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEventView> findByAggregateId(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("Aggregate id is required");
        }
        return repository.findAllByAggregateIdOrderByOccurredAtAscIdAsc(aggregateId.strip())
                .stream()
                .map(AuditEventEntity::toView)
                .toList();
    }

    private static String resolveActor(String explicitActor) {
        if (explicitActor != null && !explicitActor.isBlank()) {
            return requireText(explicitActor, "Actor", 128);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return SYSTEM_ACTOR;
        }
        String principal = authentication.getName();
        if (principal == null || principal.isBlank()) {
            return SYSTEM_ACTOR;
        }
        return requireText(principal, "Actor", 128);
    }

    private static String optionalText(String value, String field, int maximumCodePoints) {
        if (value == null) {
            return null;
        }
        return requireText(value, field, maximumCodePoints);
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
}
