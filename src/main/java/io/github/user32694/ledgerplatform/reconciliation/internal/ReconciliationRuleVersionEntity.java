package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.RuleVersionStatus;
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
@Table(name = "reconciliation_rule_version", schema = "reconciliation")
class ReconciliationRuleVersionEntity {
    @Id
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RuleVersionStatus status;

    @Column(name = "amount_tolerance_cents", nullable = false)
    private long amountToleranceCents;

    @Column(name = "query_window_hours", nullable = false)
    private int queryWindowHours;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_by", length = 128)
    private String publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ReconciliationRuleVersionEntity() {}

    static ReconciliationRuleVersionEntity draft(
            UUID ruleId,
            int versionNumber,
            long amountToleranceCents,
            int queryWindowHours,
            String operator,
            Instant now) {
        var version = new ReconciliationRuleVersionEntity();
        version.id = UUID.randomUUID();
        version.ruleId = ruleId;
        version.versionNumber = versionNumber;
        version.status = RuleVersionStatus.DRAFT;
        version.amountToleranceCents = amountToleranceCents;
        version.queryWindowHours = queryWindowHours;
        version.createdBy = operator;
        version.createdAt = normalize(now);
        return version;
    }

    UUID id() {
        return id;
    }

    UUID ruleId() {
        return ruleId;
    }

    int versionNumber() {
        return versionNumber;
    }

    RuleVersionStatus status() {
        return status;
    }

    long amountToleranceCents() {
        return amountToleranceCents;
    }

    int queryWindowHours() {
        return queryWindowHours;
    }

    String createdBy() {
        return createdBy;
    }

    Instant createdAt() {
        return createdAt;
    }

    String publishedBy() {
        return publishedBy;
    }

    Instant publishedAt() {
        return publishedAt;
    }

    void updateDraft(
            long amountToleranceCents, int queryWindowHours, String operator, Instant now) {
        if (status != RuleVersionStatus.DRAFT) {
            throw new IllegalStateException("已发布的规则版本不能修改");
        }
        this.amountToleranceCents = amountToleranceCents;
        this.queryWindowHours = queryWindowHours;
        this.createdBy = operator;
        this.createdAt = normalize(now);
    }

    void publish(String operator, Instant now) {
        if (status != RuleVersionStatus.DRAFT) {
            throw new IllegalStateException("规则版本已经发布");
        }
        status = RuleVersionStatus.PUBLISHED;
        publishedBy = operator;
        publishedAt = normalize(now);
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
