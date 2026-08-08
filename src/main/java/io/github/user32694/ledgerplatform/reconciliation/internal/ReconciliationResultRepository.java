package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResultEntity, UUID> {
    List<ReconciliationResultEntity> findAllByBatchId(UUID batchId);

    void deleteAllByBatchId(UUID batchId);
}
