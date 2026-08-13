package io.github.user32694.ledgerplatform.audit.internal;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    List<AuditEventEntity> findAllByActionAndOutcomeOrderByOccurredAtDescIdDesc(
            AuditAction action, AuditOutcome outcome, Pageable pageable);

    List<AuditEventEntity> findAllByActionOrderByOccurredAtDescIdDesc(
            AuditAction action, Pageable pageable);

    List<AuditEventEntity> findAllByOutcomeOrderByOccurredAtDescIdDesc(
            AuditOutcome outcome, Pageable pageable);

    List<AuditEventEntity> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);

    List<AuditEventEntity> findAllByAggregateIdOrderByOccurredAtAscIdAsc(String aggregateId);
}
