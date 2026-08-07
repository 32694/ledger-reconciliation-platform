package io.github.user32694.ledgerplatform.payments.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentInstructionRepository extends JpaRepository<PaymentInstructionEntity, UUID> {
    @Query(value = """
            SELECT 1
            FROM pg_advisory_xact_lock(hashtextextended(CAST(:idempotencyKey AS text), 0))
            """, nativeQuery = true)
    int acquireIdempotencyLock(@Param("idempotencyKey") String idempotencyKey);

    @Modifying
    @Query(value = """
            INSERT INTO payments.payment_instruction
                (id, idempotency_key, request_fingerprint, channel_reference, payment_type,
                 payer_account_id, payee_account_id, amount_cents, currency, status,
                 failure_reason, version, created_at, completed_at)
            VALUES
                (:id, :idempotencyKey, :requestFingerprint, :channelReference, 'TOP_UP',
                 NULL, :payeeAccountId, :amountCents, 'CNY', 'PENDING',
                 NULL, 0, :createdAt, NULL)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertPending(
            @Param("id") UUID id,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("channelReference") String channelReference,
            @Param("payeeAccountId") UUID payeeAccountId,
            @Param("amountCents") long amountCents,
            @Param("createdAt") Instant createdAt);

    Optional<PaymentInstructionEntity> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.id = :id
            """)
    Optional<PaymentInstructionEntity> findByIdForUpdate(@Param("id") UUID id);

    List<PaymentInstructionEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
