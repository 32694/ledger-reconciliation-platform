package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatchEntity, UUID> {
    Optional<ReconciliationBatchEntity> findByFileSha256(String fileSha256);

    List<ReconciliationBatchEntity> findAllByOrderByCreatedAtDescIdDesc();

    Optional<ReconciliationBatchEntity> findFirstByStatusOrderByCompletedAtDescIdDesc(
            BatchStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT batch FROM ReconciliationBatchEntity batch WHERE batch.id = :id")
    Optional<ReconciliationBatchEntity> findByIdForUpdate(@Param("id") UUID id);
}
