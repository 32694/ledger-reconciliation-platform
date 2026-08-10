package io.github.user32694.ledgerplatform.messaging.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    long countByStatus(OutboxStatus status);

    List<OutboxEventEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
