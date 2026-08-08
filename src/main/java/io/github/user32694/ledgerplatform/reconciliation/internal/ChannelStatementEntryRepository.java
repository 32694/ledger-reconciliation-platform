package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelStatementEntryRepository extends JpaRepository<ChannelStatementEntryEntity, UUID> {
    List<ChannelStatementEntryEntity> findAllByBatchIdOrderByLineNumber(UUID batchId);
}
