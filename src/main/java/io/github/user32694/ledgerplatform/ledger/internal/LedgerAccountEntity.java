package io.github.user32694.ledgerplatform.ledger.internal;

import io.github.user32694.ledgerplatform.ledger.AccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ledger_account", schema = "ledger")
class LedgerAccountEntity {
    @Id
    private UUID id;

    @Column(name = "owner_ref", nullable = false, unique = true, length = 64)
    private String ownerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccountEntity() {}

    LedgerAccountEntity(UUID id, String ownerReference, AccountType accountType, Instant createdAt) {
        this.id = id;
        this.ownerReference = ownerReference;
        this.accountType = accountType;
        this.currency = "CNY";
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    String ownerReference() {
        return ownerReference;
    }

    AccountType accountType() {
        return accountType;
    }
}
