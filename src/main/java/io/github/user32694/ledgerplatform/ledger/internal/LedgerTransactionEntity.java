package io.github.user32694.ledgerplatform.ledger.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ledger_transaction", schema = "ledger")
class LedgerTransactionEntity {
    @Id
    private UUID id;

    @Column(name = "business_reference", nullable = false, unique = true, length = 128)
    private String businessReference;

    @Column(name = "transaction_type", nullable = false, length = 32)
    private String transactionType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL)
    private List<LedgerEntryEntity> entries = new ArrayList<>();

    protected LedgerTransactionEntity() {}

    LedgerTransactionEntity(UUID id, String businessReference, String transactionType, Instant occurredAt) {
        this.id = id;
        this.businessReference = businessReference;
        this.transactionType = transactionType;
        this.occurredAt = occurredAt;
    }

    void addEntry(LedgerEntryEntity entry) {
        entries.add(entry);
    }

    UUID id() {
        return id;
    }

    String businessReference() {
        return businessReference;
    }
}
