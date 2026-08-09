package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationRuleVersionRepository
        extends JpaRepository<ReconciliationRuleVersionEntity, UUID> {
    @Query("""
            SELECT version FROM ReconciliationRuleVersionEntity version
            WHERE version.ruleId = :ruleId AND version.status = 'DRAFT'
            """)
    Optional<ReconciliationRuleVersionEntity> findDraftByRuleId(@Param("ruleId") UUID ruleId);

    @Query("""
            SELECT version FROM ReconciliationRuleVersionEntity version
            WHERE version.ruleId = :ruleId AND version.status = 'PUBLISHED'
            ORDER BY version.versionNumber DESC
            """)
    List<ReconciliationRuleVersionEntity> findPublishedByRuleIdOrderByVersionNumberDesc(
            @Param("ruleId") UUID ruleId);

    List<ReconciliationRuleVersionEntity> findAllByRuleIdOrderByVersionNumberDesc(UUID ruleId);
}
