package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.RuleScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_rule", schema = "reconciliation")
class ReconciliationRuleEntity {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private RuleScopeType scopeType;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "active_version_id")
    private UUID activeVersionId;

    @Version
    @Column(nullable = false)
    private long version;

    protected ReconciliationRuleEntity() {}

    UUID id() {
        return id;
    }

    RuleScopeType scopeType() {
        return scopeType;
    }

    UUID channelId() {
        return channelId;
    }

    UUID activeVersionId() {
        return activeVersionId;
    }

    long version() {
        return version;
    }

    void activate(UUID versionId) {
        activeVersionId = versionId;
    }
}
