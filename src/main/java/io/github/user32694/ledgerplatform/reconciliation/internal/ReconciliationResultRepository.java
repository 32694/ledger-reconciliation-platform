package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResultEntity, UUID> {
    List<ReconciliationResultEntity> findAllByBatchId(UUID batchId);

    void deleteAllByBatchId(UUID batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT result FROM ReconciliationResultEntity result WHERE result.id = :id")
    java.util.Optional<ReconciliationResultEntity> findByIdForUpdate(@Param("id") UUID id);
}
