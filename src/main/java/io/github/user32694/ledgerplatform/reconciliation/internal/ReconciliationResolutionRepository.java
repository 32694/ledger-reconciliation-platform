package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationResolutionRepository extends JpaRepository<ReconciliationResolutionEntity, UUID> {
    Optional<ReconciliationResolutionEntity> findByResultId(UUID resultId);

    List<ReconciliationResolutionEntity> findAllByResultIdIn(Collection<UUID> resultIds);
}
