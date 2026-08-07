package io.github.user32694.ledgerplatform.ledger.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {
    boolean existsByBusinessReference(String businessReference);

    @Query(value = """
            SELECT COALESCE(SUM(CASE side WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END), 0)
            FROM ledger.ledger_entry
            WHERE ledger_account_id = :ledgerAccountId
            """, nativeQuery = true)
    long liabilityBalance(@Param("ledgerAccountId") UUID ledgerAccountId);
}
