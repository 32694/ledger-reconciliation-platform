package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationChannelView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_channel", schema = "reconciliation")
class ReconciliationChannelEntity {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ReconciliationChannelEntity() {}

    UUID id() {
        return id;
    }

    String code() {
        return code;
    }

    String displayName() {
        return displayName;
    }

    boolean active() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    ReconciliationChannelView toView() {
        return new ReconciliationChannelView(id, code, displayName, active, version);
    }
}
