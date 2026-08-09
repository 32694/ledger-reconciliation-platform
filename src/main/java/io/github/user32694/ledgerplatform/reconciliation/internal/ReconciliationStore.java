package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditCommand;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
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
    private final ReconciliationRunRepository runRepository;
    private final AuditApi auditApi;

    ReconciliationStore(
            ReconciliationBatchRepository batchRepository,
            ChannelStatementEntryRepository entryRepository,
            ReconciliationResultRepository resultRepository,
            ReconciliationResolutionRepository resolutionRepository,
            ReconciliationRunRepository runRepository,
            AuditApi auditApi) {
        this.batchRepository = batchRepository;
        this.entryRepository = entryRepository;
        this.resultRepository = resultRepository;
        this.resolutionRepository = resolutionRepository;
        this.runRepository = runRepository;
        this.auditApi = auditApi;
    }

    @Transactional
    ReconciliationRunView queueRun(UUID batchId, String operator) {
        var batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch does not exist: " + batchId));
        var activeStatuses = List.of(RunStatus.QUEUED, RunStatus.RUNNING);
        var activeRun = runRepository.findFirstByBatchIdAndStatusInOrderByAttemptNumberDesc(
                batchId, activeStatuses);
        if (activeRun.isPresent()) {
            return activeRun.get().toView();
        }
        if (batch.status() != BatchStatus.IMPORTED
                && batch.status() != BatchStatus.RECONCILIATION_FAILED) {
            throw new IllegalStateException("Batch cannot start from " + batch.status());
        }
        int attemptNumber = runRepository.findFirstByBatchIdOrderByAttemptNumberDesc(batchId)
                .map(previous -> previous.toView().attemptNumber() + 1)
                .orElse(1);
        return runRepository.saveAndFlush(
                ReconciliationRunEntity.queued(batchId, attemptNumber, operator, Instant.now()))
                .toView();
    }

    @Transactional(readOnly = true)
    List<ReconciliationRunView> findRuns(UUID batchId) {
        getBatch(batchId);
        return runRepository.findAllByBatchIdOrderByAttemptNumberDesc(batchId).stream()
                .map(ReconciliationRunEntity::toView)
                .toList();
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
        auditApi.record(reconciliationAudit(
                operator,
                AuditAction.RECONCILIATION_IMPORT,
                "RECONCILIATION_BATCH",
                batch.id(),
                AuditOutcome.SUCCEEDED,
                "对账单导入成功",
                hash));
        return batch;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationBatchEntity persistImportFailure(
            String fileName,
            String hash,
            String errorMessage,
            String operator,
            Instant createdAt) {
        var batch = batchRepository.save(ReconciliationBatchEntity.importFailed(
                fileName, hash, errorMessage, operator, createdAt));
        auditApi.record(reconciliationAudit(
                operator,
                AuditAction.RECONCILIATION_IMPORT,
                "RECONCILIATION_BATCH",
                batch.id(),
                AuditOutcome.FAILED,
                "对账单导入失败",
                hash));
        return batch;
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
        auditApi.record(reconciliationAudit(
                null,
                AuditAction.RECONCILIATION_RUN,
                "RECONCILIATION_BATCH",
                batch.id(),
                AuditOutcome.SUCCEEDED,
                "对账运行成功",
                null));
        return batch.toView();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markReconciliationFailed(UUID batchId, String message) {
        var batch = findEntity(batchId);
        if (batch.status() == BatchStatus.RUNNING) {
            batch.failReconciliation(message, Instant.now());
            auditApi.record(reconciliationAudit(
                    null,
                    AuditAction.RECONCILIATION_RUN,
                    "RECONCILIATION_BATCH",
                    batch.id(),
                    AuditOutcome.FAILED,
                    "对账运行失败",
                    null));
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
        auditApi.record(reconciliationAudit(
                operator,
                AuditAction.RECONCILIATION_RESOLVE,
                "RECONCILIATION_RESULT",
                result.id(),
                AuditOutcome.SUCCEEDED,
                "对账差异处理成功",
                result.batchId().toString()));
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

    private static AuditCommand reconciliationAudit(
            String actor,
            AuditAction action,
            String aggregateType,
            UUID aggregateId,
            AuditOutcome outcome,
            String summary,
            String correlationReference) {
        return new AuditCommand(
                actor,
                action,
                aggregateType,
                aggregateId.toString(),
                outcome,
                summary,
                correlationReference);
    }
}
