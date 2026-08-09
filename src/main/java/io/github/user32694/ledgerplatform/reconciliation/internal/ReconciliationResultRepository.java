package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseProgress;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationResultRepository extends JpaRepository<ReconciliationResultEntity, UUID> {
    List<ReconciliationResultEntity> findAllByBatchId(UUID batchId);

    @Query("""
            SELECT result
            FROM ReconciliationResultEntity result
            WHERE result.resultType <> :matchedType
              AND (:type IS NULL OR result.resultType = :type)
              AND (:status IS NULL OR result.resolutionStatus = :status)
              AND (:assignee IS NULL OR result.assignedTo = :assignee)
            ORDER BY result.createdAt DESC, result.id DESC
            """)
    List<ReconciliationResultEntity> findCases(
            @Param("matchedType") ResultType matchedType,
            @Param("type") ResultType type,
            @Param("status") ResolutionStatus status,
            @Param("assignee") String assignee);

    @Query("""
            SELECT new io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseProgress(
                result.batchId,
                SUM(CASE WHEN result.resolutionStatus = :resolvedStatus THEN 1 ELSE 0 END),
                COUNT(result))
            FROM ReconciliationResultEntity result
            WHERE result.resultType <> :matchedType
            GROUP BY result.batchId
            """)
    List<ReconciliationCaseProgress> findCaseProgresses(
            @Param("matchedType") ResultType matchedType,
            @Param("resolvedStatus") ResolutionStatus resolvedStatus);

    long countByResolutionStatus(ResolutionStatus resolutionStatus);

    void deleteAllByBatchId(UUID batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT result FROM ReconciliationResultEntity result WHERE result.id = :id")
    java.util.Optional<ReconciliationResultEntity> findByIdForUpdate(@Param("id") UUID id);
}
