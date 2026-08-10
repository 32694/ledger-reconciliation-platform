package io.github.user32694.ledgerplatform.payments.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                 failure_reason, version, created_at, completed_at,
                 original_payment_id, operation_reason)
            VALUES
                (:id, :idempotencyKey, :requestFingerprint, :channelReference, :paymentType,
                 :payerAccountId, :payeeAccountId, :amountCents, 'CNY', 'PENDING',
                 NULL, 0, :createdAt, NULL, :originalPaymentId, :operationReason)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertPending(
            @Param("id") UUID id,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("channelReference") String channelReference,
            @Param("paymentType") String paymentType,
            @Param("payerAccountId") UUID payerAccountId,
            @Param("payeeAccountId") UUID payeeAccountId,
            @Param("amountCents") long amountCents,
            @Param("originalPaymentId") UUID originalPaymentId,
            @Param("operationReason") String operationReason,
            @Param("createdAt") Instant createdAt);

    Optional<PaymentInstructionEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.originalPaymentId = :originalPaymentId
              AND payment.status IN ('PENDING', 'SUCCEEDED')
            """)
    Optional<PaymentInstructionEntity> findActiveReverse(
            @Param("originalPaymentId") UUID originalPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.id = :id
            """)
    Optional<PaymentInstructionEntity> findByIdForUpdate(@Param("id") UUID id);

    List<PaymentInstructionEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.paymentType = 'TOP_UP'
              AND payment.status = 'SUCCEEDED'
              AND payment.completedAt BETWEEN :fromInclusive AND :toInclusive
            ORDER BY payment.completedAt ASC, payment.id ASC
            """)
    List<PaymentInstructionEntity> findSucceededTopUps(
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toInclusive") Instant toInclusive);

    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.paymentType = 'TOP_UP'
              AND payment.status = 'SUCCEEDED'
              AND payment.completedAt BETWEEN :fromInclusive AND :toInclusive
              AND payment.channelReference IN :references
            ORDER BY payment.completedAt ASC, payment.id ASC
            """)
    List<PaymentInstructionEntity> findSucceededTopUpsByReferences(
            @Param("references") Set<String> references,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toInclusive") Instant toInclusive);

    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.paymentType = 'TOP_UP'
              AND payment.status = 'SUCCEEDED'
              AND payment.completedAt BETWEEN :fromInclusive AND :toInclusive
            ORDER BY payment.completedAt ASC, payment.id ASC
            """)
    List<PaymentInstructionEntity> findSucceededTopUpsPage(
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toInclusive") Instant toInclusive,
            Pageable pageable);

    @Query("""
            SELECT payment
            FROM PaymentInstructionEntity payment
            WHERE payment.paymentType = 'TOP_UP'
              AND payment.status = 'SUCCEEDED'
              AND payment.completedAt BETWEEN :fromInclusive AND :toInclusive
              AND (payment.completedAt > :afterTime
                   OR (payment.completedAt = :afterTime AND payment.id > :afterId))
            ORDER BY payment.completedAt ASC, payment.id ASC
            """)
    List<PaymentInstructionEntity> findSucceededTopUpsAfter(
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toInclusive") Instant toInclusive,
            @Param("afterTime") Instant afterTime,
            @Param("afterId") UUID afterId,
            Pageable pageable);

    @Query("""
            SELECT COUNT(payment)
            FROM PaymentInstructionEntity payment
            WHERE payment.paymentType = 'TOP_UP'
              AND payment.status = 'SUCCEEDED'
              AND payment.completedAt BETWEEN :fromInclusive AND :toInclusive
            """)
    long countSucceededTopUps(
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toInclusive") Instant toInclusive);
}
