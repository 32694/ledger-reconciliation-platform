package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, UUID> {
    Optional<ReconciliationRunEntity> findFirstByBatchIdAndStatusInOrderByAttemptNumberDesc(
            UUID batchId, Collection<RunStatus> statuses);

    Optional<ReconciliationRunEntity> findFirstByBatchIdOrderByAttemptNumberDesc(UUID batchId);

    List<ReconciliationRunEntity> findAllByBatchIdOrderByAttemptNumberDesc(UUID batchId);
}
