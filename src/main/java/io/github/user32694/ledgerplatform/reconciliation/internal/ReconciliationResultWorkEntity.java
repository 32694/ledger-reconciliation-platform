package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_result_work", schema = "reconciliation")
class ReconciliationResultWorkEntity {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "statement_entry_id")
    private UUID statementEntryId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false, length = 32)
    private ResultType resultType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 16)
    private ResolutionStatus resolutionStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationResultWorkEntity() {}
}
