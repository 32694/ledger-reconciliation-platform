package io.github.user32694.ledgerplatform.ledger.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerAccountRepository extends JpaRepository<LedgerAccountEntity, UUID> {
    Optional<LedgerAccountEntity> findByOwnerReference(String ownerReference);

    @Modifying
    @Query(value = """
            INSERT INTO ledger.ledger_account
                (id, owner_ref, account_type, currency, created_at)
            VALUES
                (:id, :ownerReference, 'ASSET', 'CNY', :createdAt)
            ON CONFLICT (owner_ref) DO NOTHING
            """, nativeQuery = true)
    int insertPlatformCashAccount(
            @Param("id") UUID id,
            @Param("ownerReference") String ownerReference,
            @Param("createdAt") Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT account
            FROM LedgerAccountEntity account
            WHERE account.id IN :ids
            ORDER BY account.id
            """)
    List<LedgerAccountEntity> findAllByIdForUpdate(@Param("ids") List<UUID> ids);
}
