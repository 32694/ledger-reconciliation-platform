package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatchEntity, UUID> {
    Optional<ReconciliationBatchEntity> findByFileSha256(String fileSha256);

    List<ReconciliationBatchEntity> findAllByOrderByCreatedAtDescIdDesc();
}
