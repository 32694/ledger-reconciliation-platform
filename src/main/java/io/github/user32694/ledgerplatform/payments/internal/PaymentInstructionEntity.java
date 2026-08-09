package io.github.user32694.ledgerplatform.payments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_instruction", schema = "payments")
class PaymentInstructionEntity {
    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String requestFingerprint;

    @Column(name = "channel_reference", nullable = false, unique = true, length = 64)
    private String channelReference;

    @Column(name = "payment_type", nullable = false, length = 24)
    private String paymentType;

    @Column(name = "payer_account_id")
    private UUID payerAccountId;

    @Column(name = "payee_account_id", nullable = false)
    private UUID payeeAccountId;

    @Column(name = "original_payment_id")
    private UUID originalPaymentId;

    @Column(name = "operation_reason", length = 500)
    private String operationReason;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PaymentInstructionEntity() {}

    void succeed(Instant completedAt) {
        status = "SUCCEEDED";
        failureReason = null;
        this.completedAt = completedAt.truncatedTo(ChronoUnit.MICROS);
    }

    void fail(String failureReason, Instant completedAt) {
        status = "FAILED";
        this.failureReason = failureReason;
        this.completedAt = completedAt.truncatedTo(ChronoUnit.MICROS);
    }

    UUID id() {
        return id;
    }

    String requestFingerprint() {
        return requestFingerprint;
    }

    String channelReference() {
        return channelReference;
    }

    String paymentType() {
        return paymentType;
    }

    UUID payerAccountId() {
        return payerAccountId;
    }

    UUID payeeAccountId() {
        return payeeAccountId;
    }

    UUID originalPaymentId() {
        return originalPaymentId;
    }

    String operationReason() {
        return operationReason;
    }

    long amountCents() {
        return amountCents;
    }

    String status() {
        return status;
    }

    String failureReason() {
        return failureReason;
    }

    Instant occurredAt() {
        return completedAt;
    }
}
