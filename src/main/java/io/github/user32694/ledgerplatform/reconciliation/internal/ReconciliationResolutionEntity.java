package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_resolution", schema = "reconciliation")
class ReconciliationResolutionEntity {
    @Id
    private UUID id;

    @Column(name = "result_id", nullable = false, unique = true)
    private UUID resultId;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 2000)
    private String note;

    @Column(nullable = false, length = 128)
    private String operator;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code", nullable = false, length = 32)
    private ResolutionCode resolutionCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationResolutionEntity() {}

    static ReconciliationResolutionEntity resolve(
            UUID resultId,
            ResolutionCode resolutionCode,
            String note,
            String operator,
            Instant createdAt) {
        var entity = new ReconciliationResolutionEntity();
        entity.id = UUID.randomUUID();
        entity.resultId = resultId;
        entity.action = "RESOLVE";
        entity.note = note;
        entity.operator = operator;
        entity.resolutionCode = resolutionCode;
        entity.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        return entity;
    }

    String note() {
        return note;
    }

    String operator() {
        return operator;
    }

    Instant createdAt() {
        return createdAt;
    }
}
