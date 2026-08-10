package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationChannelRepository
        extends JpaRepository<ReconciliationChannelEntity, UUID> {
    Optional<ReconciliationChannelEntity> findByCode(String code);

    List<ReconciliationChannelEntity> findAllByCodeNotOrderByCodeAsc(String excludedCode);

    List<ReconciliationChannelEntity> findAllByActiveTrueAndCodeNotOrderByCodeAsc(
            String excludedCode);
}
