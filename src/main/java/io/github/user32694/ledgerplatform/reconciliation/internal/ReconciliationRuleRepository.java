package io.github.user32694.ledgerplatform.reconciliation.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationRuleRepository extends JpaRepository<ReconciliationRuleEntity, UUID> {
    @Query("SELECT rule FROM ReconciliationRuleEntity rule WHERE rule.scopeType = 'DEFAULT'")
    Optional<ReconciliationRuleEntity> findDefault();

    Optional<ReconciliationRuleEntity> findByChannelId(UUID channelId);

    List<ReconciliationRuleEntity> findAllByOrderByScopeTypeAscChannelIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rule FROM ReconciliationRuleEntity rule WHERE rule.id = :id")
    Optional<ReconciliationRuleEntity> findByIdForUpdate(@Param("id") UUID id);
}
