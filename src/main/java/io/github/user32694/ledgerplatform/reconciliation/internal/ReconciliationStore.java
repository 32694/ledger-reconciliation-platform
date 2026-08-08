package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
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
    private final ReconciliationResultRepository resultRepository;
    private final ReconciliationResolutionRepository resolutionRepository;

    ReconciliationStore(
            ReconciliationBatchRepository batchRepository,
            ChannelStatementEntryRepository entryRepository,
            ReconciliationResultRepository resultRepository,
            ReconciliationResolutionRepository resolutionRepository) {
        this.batchRepository = batchRepository;
        this.entryRepository = entryRepository;
        this.resultRepository = resultRepository;
        this.resolutionRepository = resolutionRepository;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationBatchView markRunningOrReturnCompleted(UUID batchId) {
        var batch = findEntity(batchId);
        if (batch.status() == BatchStatus.COMPLETED) {
            return batch.toView();
        }
        if (batch.status() == BatchStatus.RUNNING) {
            throw new IllegalStateException("Batch is already running");
        }
        batch.start(Instant.now());
        return batch.toView();
    }

    @Transactional(readOnly = true)
    List<ReconciliationMatcher.StatementEntrySnapshot> findStatementEntries(UUID batchId) {
        return entryRepository.findAllByBatchIdOrderByLineNumber(batchId).stream()
                .map(entry -> new ReconciliationMatcher.StatementEntrySnapshot(
                        entry.id(),
                        entry.lineNumber(),
                        entry.channelTransactionId(),
                        entry.amountCents(),
                        entry.occurredAt()))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationBatchView replaceResultsAndComplete(
            UUID batchId, List<ReconciliationMatcher.ResultDraft> drafts) {
        var batch = findEntity(batchId);
        resultRepository.deleteAllByBatchId(batchId);
        resultRepository.flush();
        resultRepository.saveAll(drafts.stream()
                .map(draft -> ReconciliationResultEntity.from(batchId, draft, Instant.now()))
                .toList());
        int matchedRows = (int) drafts.stream()
                .filter(draft -> draft.resultType().name().equals("MATCHED"))
                .count();
        batch.complete(matchedRows, drafts.size() - matchedRows, Instant.now());
        return batch.toView();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markReconciliationFailed(UUID batchId, String message) {
        var batch = findEntity(batchId);
        if (batch.status() == BatchStatus.RUNNING) {
            batch.failReconciliation(message, Instant.now());
        }
    }

    @Transactional(readOnly = true)
    List<ReconciliationResultEntity> findResults(UUID batchId) {
        return resultRepository.findAllByBatchId(batchId);
    }

    @Transactional
    ReconciliationResultEntity resolveResult(UUID resultId, String note, String operator) {
        var result = resultRepository.findByIdForUpdate(resultId)
                .orElseThrow(() -> new IllegalArgumentException("Result does not exist: " + resultId));
        var resolution = result.resolve(note, operator, Instant.now());
        resolutionRepository.saveAndFlush(resolution);
        return result;
    }

    @Transactional(readOnly = true)
    Optional<ReconciliationResolutionEntity> findResolution(UUID resultId) {
        return resolutionRepository.findByResultId(resultId);
    }

    private ReconciliationBatchEntity findEntity(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch does not exist: " + batchId));
    }
}
