package io.github.user32694.ledgerplatform.accounts.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customer_account", schema = "accounts")
class CustomerAccountEntity {
    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 32)
    private String accountNumber;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "ledger_account_id", nullable = false, unique = true)
    private UUID ledgerAccountId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerAccountEntity() {}

    CustomerAccountEntity(
            UUID id,
            String accountNumber,
            String ownerName,
            UUID ledgerAccountId,
            Instant createdAt) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.status = "ACTIVE";
        this.currency = "CNY";
        this.ledgerAccountId = ledgerAccountId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    UUID id() {
        return id;
    }

    String accountNumber() {
        return accountNumber;
    }

    String ownerName() {
        return ownerName;
    }

    String status() {
        return status;
    }

    UUID ledgerAccountId() {
        return ledgerAccountId;
    }
}
