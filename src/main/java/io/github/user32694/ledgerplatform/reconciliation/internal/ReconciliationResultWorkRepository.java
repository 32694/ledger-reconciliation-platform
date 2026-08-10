package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationResultWorkRepository
        extends JpaRepository<ReconciliationResultWorkEntity, UUID> {
    @Query("""
            SELECT work.paymentId
            FROM ReconciliationResultWorkEntity work
            WHERE work.runId = :runId AND work.paymentId IN :paymentIds
            """)
    Set<UUID> findConsumedPaymentIds(
            @Param("runId") UUID runId, @Param("paymentIds") Collection<UUID> paymentIds);
}
