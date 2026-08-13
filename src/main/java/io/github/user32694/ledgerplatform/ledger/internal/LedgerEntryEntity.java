package io.github.user32694.ledgerplatform.ledger.internal;

import io.github.user32694.ledgerplatform.ledger.EntrySide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry", schema = "ledger")
class LedgerEntryEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransactionEntity transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false)
    private LedgerAccountEntity ledgerAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private EntrySide side;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {}

    LedgerEntryEntity(
            UUID id,
            LedgerTransactionEntity transaction,
            LedgerAccountEntity ledgerAccount,
            EntrySide side,
            long amountCents,
            Instant createdAt) {
        this.id = id;
        this.transaction = transaction;
        this.ledgerAccount = ledgerAccount;
        this.side = side;
        this.amountCents = amountCents;
        this.createdAt = createdAt;
    }

    EntrySide side() {
        return side;
    }

    UUID id() {
        return id;
    }

    LedgerAccountEntity ledgerAccount() {
        return ledgerAccount;
    }

    long amountCents() {
        return amountCents;
    }

    Instant createdAt() {
        return createdAt;
    }
}
