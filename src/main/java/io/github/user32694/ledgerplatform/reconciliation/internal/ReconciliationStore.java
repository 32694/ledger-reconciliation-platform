package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditCommand;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.messaging.EventType;
import io.github.user32694.ledgerplatform.messaging.OutboxApi;
import io.github.user32694.ledgerplatform.messaging.OutboxCommand;
import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseEventView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseProgress;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionCode;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReconciliationStore {
    private final ReconciliationBatchRepository batchRepository;
    private final ChannelStatementEntryRepository entryRepository;
    private final ReconciliationResultRepository resultRepository;
    private final ReconciliationResolutionRepository resolutionRepository;
    private final ReconciliationCaseEventRepository caseEventRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationResultWorkRepository workRepository;
    private final AuditApi auditApi;
    private final OutboxApi outboxApi;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    ReconciliationStore(
            ReconciliationBatchRepository batchRepository,
            ChannelStatementEntryRepository entryRepository,
            ReconciliationResultRepository resultRepository,
            ReconciliationResolutionRepository resolutionRepository,
            ReconciliationCaseEventRepository caseEventRepository,
            ReconciliationRunRepository runRepository,
            ReconciliationResultWorkRepository workRepository,
            AuditApi auditApi,
            OutboxApi outboxApi,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate) {
        this.batchRepository = batchRepository;
        this.entryRepository = entryRepository;
        this.resultRepository = resultRepository;
        this.resolutionRepository = resolutionRepository;
        this.caseEventRepository = caseEventRepository;
        this.runRepository = runRepository;
        this.workRepository = workRepository;
        this.auditApi = auditApi;
        this.outboxApi = outboxApi;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    QueuedRun queueRun(UUID batchId, String operator) {
        var batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch does not exist: " + batchId));
        var activeStatuses = List.of(RunStatus.QUEUED, RunStatus.RUNNING);
        var activeRun = runRepository.findFirstByBatchIdAndStatusInOrderByAttemptNumberDesc(
                batchId, activeStatuses);
        if (activeRun.isPresent()) {
            return new QueuedRun(activeRun.get().toView(), false);
        }
        if (batch.status() == BatchStatus.COMPLETED) {
            return runRepository.findFirstByBatchIdOrderByAttemptNumberDesc(batchId)
                    .map(run -> new QueuedRun(run.toView(), false))
                    .orElseThrow(() -> new IllegalStateException("Completed batch has no run history"));
        }
        if (batch.status() != BatchStatus.IMPORTED
                && batch.status() != BatchStatus.RECONCILIATION_FAILED) {
            throw new IllegalStateException("Batch cannot start from " + batch.status());
        }
        int attemptNumber = runRepository.findFirstByBatchIdOrderByAttemptNumberDesc(batchId)
                .map(previous -> previous.toView().attemptNumber() + 1)
                .orElse(1);
        var queued = runRepository.saveAndFlush(
                ReconciliationRunEntity.queued(batchId, attemptNumber, operator, Instant.now()));
        return new QueuedRun(queued.toView(), true);
    }

    @Transactional(readOnly = true)
    Optional<Long> restartableExecutionId(UUID runId) {
        var run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run does not exist: " + runId));
        if (run.status() != RunStatus.FAILED) {
            throw new IllegalStateException("Run is not restartable: " + runId);
        }
        return Optional.ofNullable(run.batchJobExecutionId());
    }

    @Transactional(readOnly = true)
    ReconciliationRunView getRun(UUID runId) {
        return runRepository.findById(runId)
                .map(ReconciliationRunEntity::toView)
                .orElseThrow(() -> new IllegalArgumentException("Run does not exist: " + runId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean claimQueuedRecovery(UUID runId, Long jobInstanceId, Long jobExecutionId) {
        var run = findRunForUpdate(runId);
        if (run.status() != RunStatus.QUEUED
                || (run.batchJobExecutionId() != null
                        && !java.util.Objects.equals(run.batchJobExecutionId(), jobExecutionId))) {
            return false;
        }
        var batch = findEntity(run.batchId());
        var now = Instant.now();
        run.start(jobInstanceId, jobExecutionId, now);
        batch.start(now);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordSubmittedExecution(UUID runId, Long jobInstanceId, Long jobExecutionId) {
        var run = findRunForUpdate(runId);
        if (run.batchJobExecutionId() == null
                || java.util.Objects.equals(run.batchJobExecutionId(), jobExecutionId)) {
            run.attachExecution(jobInstanceId, jobExecutionId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean clearMissingFailedExecution(UUID runId, Long expectedExecutionId) {
        var run = findRunForUpdate(runId);
        if (run.status() != RunStatus.FAILED
                || !java.util.Objects.equals(run.batchJobExecutionId(), expectedExecutionId)) {
            return false;
        }
        run.clearExecution();
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean claimStaleRunningRecovery(
            UUID runId,
            Long expectedExecutionId,
            int expectedRestartCount,
            boolean clearExecution,
            String message) {
        var run = findRunForUpdate(runId);
        if (run.status() != RunStatus.RUNNING
                || !java.util.Objects.equals(run.batchJobExecutionId(), expectedExecutionId)
                || run.restartCount() != expectedRestartCount) {
            return false;
        }
        if (!clearExecution && expectedRestartCount == 0) {
            run.reserveRestart();
            return true;
        }
        if (clearExecution) {
            run.clearExecution();
        }
        failLockedRun(run, message);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationRunView markRunRunning(UUID runId) {
        return beginRun(runId, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationRunView beginRun(UUID runId, Long jobInstanceId, Long jobExecutionId) {
        var run = findRunForUpdate(runId);
        var batch = findEntity(run.batchId());
        if (run.status() == RunStatus.RUNNING) {
            if (batch.status() != BatchStatus.RUNNING) {
                throw new IllegalStateException(
                        "Running run requires a RUNNING batch, but was " + batch.status());
            }
            if (jobInstanceId != null || jobExecutionId != null) {
                run.attachExecution(jobInstanceId, jobExecutionId);
            }
            return run.toView();
        }
        boolean initialStart = run.status() == RunStatus.QUEUED;
        var startedAt = Instant.now();
        run.start(jobInstanceId, jobExecutionId, startedAt);
        batch.start(startedAt);
        if (initialStart) {
            auditApi.record(reconciliationAudit(
                    run.requestedBy(),
                    AuditAction.RECONCILIATION_RUN,
                    "RECONCILIATION_BATCH",
                    batch.id(),
                    AuditOutcome.SUCCEEDED,
                    "对账运行已启动",
                    run.id().toString()));
        }
        return run.toView();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void initializeRunTotal(UUID runId, int totalItems) {
        findRunForUpdate(runId).setTotalItems(totalItems);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordChunkProgress(UUID runId, String stepName, int processedInStep) {
        var run = findRunForUpdate(runId);
        int previousStepRead = switch (stepName) {
            case "matchStatementEntriesStep" -> 0;
            case "findInternalOnlyPaymentsStep" -> findEntity(run.batchId()).totalRows();
            default -> run.toView().processedItems();
        };
        run.updateProgress(stepName, previousStepRead + Math.max(0, processedInStep));
    }

    @Transactional
    void writeWorkResults(
            UUID runId, UUID batchId, List<ReconciliationWorkResult> results) {
        if (results.isEmpty()) {
            return;
        }
        var createdAt = Instant.now();
        jdbcTemplate.batchUpdate("""
                INSERT INTO reconciliation.reconciliation_result_work
                    (id, run_id, batch_id, statement_entry_id, payment_id,
                     result_type, resolution_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, results, results.size(), (statement, result) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, runId);
                    statement.setObject(3, batchId);
                    statement.setObject(4, result.statementEntryId());
                    statement.setObject(5, result.paymentId());
                    statement.setString(6, result.resultType().name());
                    statement.setString(7, result.resolutionStatus().name());
                    statement.setTimestamp(8, Timestamp.from(createdAt));
                });
    }

    @Transactional
    void clearWorkResults(UUID runId) {
        jdbcTemplate.update("""
                DELETE FROM reconciliation.reconciliation_result_work WHERE run_id = ?
                """, runId);
    }

    @Transactional(readOnly = true)
    Set<UUID> findConsumedPaymentIds(UUID runId, Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Set.of();
        }
        return workRepository.findConsumedPaymentIds(runId, paymentIds);
    }

    @Transactional
    ReconciliationBatchView promoteWorkResults(UUID runId) {
        var run = findRunForUpdate(runId);
        var batch = batchRepository.findByIdForUpdate(run.batchId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Batch does not exist: " + run.batchId()));
        int expectedStatementRows = batch.totalRows();
        int expectedPaymentRows = run.toView().totalItems() - expectedStatementRows;
        int actualStatementRows = jdbcTemplate.queryForObject("""
                SELECT count(statement_entry_id)
                FROM reconciliation.reconciliation_result_work WHERE run_id = ?
                """, Integer.class, runId);
        int actualPaymentRows = jdbcTemplate.queryForObject("""
                SELECT count(payment_id)
                FROM reconciliation.reconciliation_result_work WHERE run_id = ?
                """, Integer.class, runId);
        if (expectedPaymentRows < 0
                || actualStatementRows != expectedStatementRows
                || actualPaymentRows != expectedPaymentRows) {
            throw new IllegalStateException("Incomplete reconciliation work for run " + runId);
        }
        resultRepository.deleteAllByBatchId(batch.id());
        resultRepository.flush();
        jdbcTemplate.update("""
                INSERT INTO reconciliation.reconciliation_result
                    (id, batch_id, statement_entry_id, payment_id, result_type,
                     resolution_status, created_at)
                SELECT id, batch_id, statement_entry_id, payment_id, result_type,
                       resolution_status, created_at
                FROM reconciliation.reconciliation_result_work
                WHERE run_id = ?
                """, runId);
        int matchedRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM reconciliation.reconciliation_result_work
                WHERE run_id = ? AND result_type = 'MATCHED'
                """, Integer.class, runId);
        int totalRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM reconciliation.reconciliation_result_work WHERE run_id = ?
                """, Integer.class, runId);
        int differenceRows = totalRows - matchedRows;
        var now = Instant.now();
        batch.complete(matchedRows, differenceRows, now);
        run.succeed(matchedRows, differenceRows, now);
        auditApi.record(reconciliationAudit(
                run.requestedBy(),
                AuditAction.RECONCILIATION_RUN,
                "RECONCILIATION_BATCH",
                batch.id(),
                AuditOutcome.SUCCEEDED,
                "对账运行成功",
                run.id().toString()));
        outboxApi.append(new OutboxCommand(
                EventType.RECONCILIATION_COMPLETED,
                "RECONCILIATION_BATCH",
                batch.id().toString(),
                1,
                Map.of(
                        "batchId", batch.id().toString(),
                        "runId", run.id().toString(),
                        "matchedRows", matchedRows,
                        "differenceRows", differenceRows),
                now));
        jdbcTemplate.update("""
                DELETE FROM reconciliation.reconciliation_result_work WHERE batch_id = ?
                """, batch.id());
        return batch.toView();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failRun(UUID runId, String message) {
        var run = findRunForUpdate(runId);
        failLockedRun(run, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordRunRecovery(UUID runId, String actor, String summary) {
        var run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run does not exist: " + runId));
        auditApi.record(reconciliationAudit(
                actor,
                AuditAction.RECONCILIATION_RUN,
                "RECONCILIATION_BATCH",
                run.batchId(),
                AuditOutcome.SUCCEEDED,
                summary,
                run.id().toString()));
    }

    private void failLockedRun(ReconciliationRunEntity run, String message) {
        if (!run.fail(message, Instant.now())) {
            return;
        }
        var batch = findEntity(run.batchId());
        if (batch.status() == BatchStatus.RUNNING) {
            batch.failReconciliation(message, Instant.now());
        } else {
            batch.failQueuedReconciliation(message, Instant.now());
        }
        auditApi.record(reconciliationAudit(
                run.requestedBy(),
                AuditAction.RECONCILIATION_RUN,
                "RECONCILIATION_BATCH",
                batch.id(),
                AuditOutcome.FAILED,
                "对账运行失败",
                run.id().toString()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failRunUnlessSucceeded(UUID runId, String message) {
        var run = findRunForUpdate(runId);
        if (run.status() != RunStatus.SUCCEEDED) {
            failRun(runId, message);
        }
    }

    @Transactional(readOnly = true)
    List<ReconciliationRunView> findRecoverableRuns(Instant recoveryCutoff) {
        return runRepository.findAllRecoverableBefore(
                        List.of(RunStatus.QUEUED, RunStatus.RUNNING), recoveryCutoff)
                .stream()
                .map(ReconciliationRunEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    List<ReconciliationRunView> findFailedRunsWithExecution(Instant recoveryCutoff) {
        return runRepository.findAllFailedWithExecutionBefore(RunStatus.FAILED, recoveryCutoff)
                .stream()
                .map(ReconciliationRunEntity::toView)
                .toList();
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
            UUID channelId,
            UUID ruleVersionId,
            ParsedStatement parsed,
            String operator,
            Instant createdAt) {
        var batch = batchRepository.save(ReconciliationBatchEntity.imported(
                fileName,
                hash,
                channelId,
                ruleVersionId,
                parsed.periodStart(),
                parsed.periodEnd(),
                parsed.entries().size(),
                operator,
                createdAt));
        batchRepository.flush();
        entityManager.refresh(batch);
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
            UUID channelId,
            UUID ruleVersionId,
            String errorMessage,
            String operator,
            Instant createdAt) {
        var batch = batchRepository.save(ReconciliationBatchEntity.importFailed(
                fileName, hash, channelId, ruleVersionId, errorMessage, operator, createdAt));
        batchRepository.flush();
        entityManager.refresh(batch);
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

    @Transactional(readOnly = true)
    ReconciliationBatchView getBatchForRun(UUID runId) {
        var run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run does not exist: " + runId));
        return getBatch(run.batchId());
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

    @Transactional(readOnly = true)
    List<ReconciliationResultEntity> findResults(UUID batchId) {
        return resultRepository.findAllByBatchId(batchId);
    }

    @Transactional(readOnly = true)
    List<ReconciliationResultEntity> findCases(
            io.github.user32694.ledgerplatform.reconciliation.ResultType type,
            ResolutionStatus status,
            String assignee) {
        return resultRepository.findCases(
                io.github.user32694.ledgerplatform.reconciliation.ResultType.MATCHED,
                type,
                status,
                assignee);
    }

    @Transactional(readOnly = true)
    List<ReconciliationCaseProgress> findCaseProgresses() {
        return resultRepository.findCaseProgresses(
                io.github.user32694.ledgerplatform.reconciliation.ResultType.MATCHED,
                ResolutionStatus.RESOLVED);
    }

    @Transactional(readOnly = true)
    ReconciliationResultEntity getResult(UUID resultId) {
        return resultRepository.findById(resultId)
                .orElseThrow(() -> new IllegalArgumentException("Result does not exist: " + resultId));
    }

    @Transactional(readOnly = true)
    Optional<ReconciliationBatchView> findLatestCompletedBatch() {
        return batchRepository.findFirstByStatusOrderByCompletedAtDescIdDesc(BatchStatus.COMPLETED)
                .map(ReconciliationBatchEntity::toView);
    }

    @Transactional(readOnly = true)
    long countResults(ResolutionStatus status) {
        return resultRepository.countByResolutionStatus(status);
    }

    @Transactional(readOnly = true)
    long countFailedRuns() {
        return runRepository.countByStatus(RunStatus.FAILED);
    }

    @Transactional
    ReconciliationResultEntity claimResult(UUID resultId, String operator) {
        var result = findResultForUpdate(resultId);
        var now = Instant.now();
        if (!result.claim(operator, now)) {
            return result;
        }
        String actor = operator.strip();
        caseEventRepository.saveAndFlush(
                ReconciliationCaseEventEntity.claimed(result.id(), actor, now));
        recordCaseAudit(result, actor, AuditAction.RECONCILIATION_CASE_CLAIM, "对账差异已认领");
        return result;
    }

    @Transactional
    ReconciliationResultEntity releaseResult(UUID resultId, String operator) {
        var result = findResultForUpdate(resultId);
        result.release(operator);
        String actor = operator.strip();
        var now = Instant.now();
        caseEventRepository.saveAndFlush(
                ReconciliationCaseEventEntity.released(result.id(), actor, now));
        recordCaseAudit(result, actor, AuditAction.RECONCILIATION_CASE_RELEASE, "对账差异已取消认领");
        return result;
    }

    @Transactional
    ReconciliationResultEntity resolveResult(
            UUID resultId, ResolutionCode resolutionCode, String note, String operator) {
        var result = findResultForUpdate(resultId);
        var now = Instant.now();
        var resolution = result.resolve(resolutionCode, note, operator, now);
        resolutionRepository.saveAndFlush(resolution);
        String actor = operator.strip();
        caseEventRepository.saveAndFlush(ReconciliationCaseEventEntity.resolved(
                result.id(), actor, resolutionCode, note.strip(), now));
        recordCaseAudit(result, actor, AuditAction.RECONCILIATION_CASE_RESOLVE, "对账差异已解决");
        return result;
    }

    @Transactional(readOnly = true)
    List<ReconciliationCaseEventView> findCaseEvents(UUID resultId) {
        if (!resultRepository.existsById(resultId)) {
            throw new IllegalArgumentException("Result does not exist: " + resultId);
        }
        return caseEventRepository.findAllByResultIdOrderByCreatedAtAscIdAsc(resultId).stream()
                .map(ReconciliationCaseEventEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    Optional<ReconciliationResolutionEntity> findResolution(UUID resultId) {
        return resolutionRepository.findByResultId(resultId);
    }

    @Transactional(readOnly = true)
    Map<UUID, ReconciliationResolutionEntity> findResolutions(Collection<UUID> resultIds) {
        return resolutionRepository.findAllByResultIdIn(resultIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReconciliationResolutionEntity::resultId,
                        java.util.function.Function.identity()));
    }

    private ReconciliationBatchEntity findEntity(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch does not exist: " + batchId));
    }

    private ReconciliationRunEntity findRunForUpdate(UUID runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run does not exist: " + runId));
    }

    private ReconciliationResultEntity findResultForUpdate(UUID resultId) {
        return resultRepository.findByIdForUpdate(resultId)
                .orElseThrow(() -> new IllegalArgumentException("Result does not exist: " + resultId));
    }

    private void recordCaseAudit(
            ReconciliationResultEntity result,
            String actor,
            AuditAction action,
            String summary) {
        auditApi.record(reconciliationAudit(
                actor,
                action,
                "RECONCILIATION_RESULT",
                result.id(),
                AuditOutcome.SUCCEEDED,
                summary,
                result.batchId().toString()));
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

    record QueuedRun(ReconciliationRunView run, boolean created) {}
}
