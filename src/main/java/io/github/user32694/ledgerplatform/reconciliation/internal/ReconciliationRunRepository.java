package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, UUID> {
    Optional<ReconciliationRunEntity> findFirstByBatchIdAndStatusInOrderByAttemptNumberDesc(
            UUID batchId, Collection<RunStatus> statuses);

    Optional<ReconciliationRunEntity> findFirstByBatchIdOrderByAttemptNumberDesc(UUID batchId);

    List<ReconciliationRunEntity> findAllByBatchIdOrderByAttemptNumberDesc(UUID batchId);

    @Query("""
            SELECT run FROM ReconciliationRunEntity run
            WHERE run.status IN :statuses AND run.requestedAt < :recoveryCutoff
            ORDER BY run.requestedAt ASC, run.id ASC
            """)
    List<ReconciliationRunEntity> findAllRecoverableBefore(
            @Param("statuses") Collection<RunStatus> statuses,
            @Param("recoveryCutoff") java.time.Instant recoveryCutoff);

    @Query("""
            SELECT run FROM ReconciliationRunEntity run
            WHERE run.status = :status
              AND run.batchJobExecutionId IS NOT NULL
              AND run.requestedAt < :recoveryCutoff
            ORDER BY run.requestedAt ASC, run.id ASC
            """)
    List<ReconciliationRunEntity> findAllFailedWithExecutionBefore(
            @Param("status") RunStatus status,
            @Param("recoveryCutoff") java.time.Instant recoveryCutoff);

    long countByStatus(RunStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT run FROM ReconciliationRunEntity run WHERE run.id = :id")
    Optional<ReconciliationRunEntity> findByIdForUpdate(@Param("id") UUID id);
}
