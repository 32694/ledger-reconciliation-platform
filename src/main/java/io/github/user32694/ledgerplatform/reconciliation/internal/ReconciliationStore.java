package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReconciliationStore {
    private final ReconciliationBatchRepository batchRepository;
    private final ChannelStatementEntryRepository entryRepository;

    ReconciliationStore(
            ReconciliationBatchRepository batchRepository,
            ChannelStatementEntryRepository entryRepository) {
        this.batchRepository = batchRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional(readOnly = true)
    Optional<ReconciliationBatchEntity> findByHash(String hash) {
        return batchRepository.findByFileSha256(hash);
    }

    @Transactional
    ReconciliationBatchEntity persistImported(
            String fileName,
            String hash,
            ParsedStatement parsed,
            String operator,
            Instant createdAt) {
        var batch = batchRepository.save(ReconciliationBatchEntity.imported(
                fileName,
                hash,
                parsed.periodStart(),
                parsed.periodEnd(),
                parsed.entries().size(),
                operator,
                createdAt));
        entryRepository.saveAllAndFlush(parsed.entries().stream()
                .map(entry -> ChannelStatementEntryEntity.from(batch.id(), entry))
                .toList());
        return batch;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationBatchEntity persistImportFailure(
            String fileName,
            String hash,
            String errorMessage,
            String operator,
            Instant createdAt) {
        return batchRepository.save(ReconciliationBatchEntity.importFailed(
                fileName, hash, errorMessage, operator, createdAt));
    }

    @Transactional(readOnly = true)
    List<ReconciliationBatchView> findBatches() {
        return batchRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(ReconciliationBatchEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    ReconciliationBatchView getBatch(UUID batchId) {
        return batchRepository.findById(batchId)
                .map(ReconciliationBatchEntity::toView)
                .orElseThrow(() -> new IllegalArgumentException("Batch does not exist: " + batchId));
    }
}
