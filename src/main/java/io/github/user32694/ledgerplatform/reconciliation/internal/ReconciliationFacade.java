package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseDetailsView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseEventView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseProgress;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationOperationsSummary;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationResultView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionCode;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.batch.core.launch.JobOperator;

@Service
public class ReconciliationFacade implements ReconciliationApi {
    private final ReconciliationImportService importService;
    private final ReconciliationStore store;
    private final ReconciliationJobLauncher jobLauncher;
    private final JobOperator jobOperator;
    private final PaymentsApi paymentsApi;

    public ReconciliationFacade(
            ReconciliationImportService importService,
            ReconciliationStore store,
            ReconciliationJobLauncher jobLauncher,
            JobOperator jobOperator,
            PaymentsApi paymentsApi) {
        this.importService = importService;
        this.store = store;
        this.jobLauncher = jobLauncher;
        this.jobOperator = jobOperator;
        this.paymentsApi = paymentsApi;
    }

    @Override
    public ReconciliationBatchView importStatement(StatementUpload upload) {
        // Facade 只编排用例；文件解析、SHA-256 幂等和批次落库由 ImportService 负责。
        return importService.importStatement(upload);
    }

    @Override
    public List<ReconciliationBatchView> findBatches() {
        return store.findBatches();
    }

    @Override
    public ReconciliationBatchView getBatch(UUID batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        return store.getBatch(batchId);
    }

    @Override
    public ReconciliationRunView startRun(UUID batchId, String operator) {
        // queueRun 负责保证同一批次不会重复排队；只有真正创建新运行时才提交 Batch Job。
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("Operator is required");
        }
        var queued = store.queueRun(batchId, operator.strip());
        if (queued.created()) {
            jobLauncher.submit(queued.run().id());
        }
        return queued.run();
    }

    @Override
    public ReconciliationRunView restartRun(UUID runId, String operator) {
        // 优先沿用 Spring Batch 原 execution 做 restart，只有没有可恢复 execution 时才新建提交。
        if (runId == null) {
            throw new IllegalArgumentException("Run id is required");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("Operator is required");
        }
        String normalizedOperator = operator.strip();
        try {
            var executionId = store.restartableExecutionId(runId);
            boolean submitted;
            if (executionId.isPresent()) {
                jobOperator.restart(executionId.get());
                submitted = true;
            } else {
                submitted = jobLauncher.submit(runId);
            }
            if (submitted) {
                store.recordRunRecovery(runId, normalizedOperator, "对账运行由管理员恢复");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Run cannot restart: " + runId, exception);
        }
        return store.getRun(runId);
    }

    @Override
    public List<ReconciliationRunView> findRuns(UUID batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        return store.findRuns(batchId);
    }

    @Override
    public List<ReconciliationResultView> findResults(
            UUID batchId, ResultType resultType, ResolutionStatus resolutionStatus) {
        // 结果查询需要把批次、渠道流水和支付快照拼成页面模型，因此在这里做跨模块组装。
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        var batch = store.getBatch(batchId);
        var entries = store.findStatementEntries(batchId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReconciliationMatcher.StatementEntrySnapshot::id, Function.identity()));
        var payments = findPayments(batch);
        var paymentMap = payments.stream().collect(java.util.stream.Collectors.toMap(
                PaymentView::id, Function.identity()));
        return store.findResults(batchId).stream()
                .filter(result -> resultType == null || result.resultType() == resultType)
                .filter(result -> resolutionStatus == null || result.resolutionStatus() == resolutionStatus)
                .map(result -> toResultView(result, entries, paymentMap))
                .sorted(Comparator
                        .comparingInt((ReconciliationResultView result) -> result.resultType() == ResultType.MATCHED ? 1 : 0)
                        .thenComparing(ReconciliationResultView::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ReconciliationResultView::channelTransactionId,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(ReconciliationResultView::paymentId,
                                Comparator.nullsLast(UUID::compareTo)))
                .toList();
    }

    @Override
    public ReconciliationResultView claim(UUID resultId, String operator) {
        // 认领、释放、解决都委托给 Store 的条件更新，避免并发操作覆盖状态。
        requireResultId(resultId);
        return toResultView(store.claimResult(resultId, operator));
    }

    @Override
    public ReconciliationResultView release(UUID resultId, String operator) {
        requireResultId(resultId);
        return toResultView(store.releaseResult(resultId, operator));
    }

    @Override
    public ReconciliationResultView resolve(
            UUID resultId, ResolutionCode resolutionCode, String note, String operator) {
        requireResultId(resultId);
        return toResultView(store.resolveResult(resultId, resolutionCode, note, operator));
    }

    @Override
    public List<ReconciliationCaseEventView> findCaseEvents(UUID resultId) {
        requireResultId(resultId);
        return store.findCaseEvents(resultId);
    }

    @Override
    public List<ReconciliationCaseView> findCases(
            ResultType type, ResolutionStatus status, String assignee) {
        String normalizedAssignee = assignee == null ? null : assignee.strip();
        if (normalizedAssignee != null && normalizedAssignee.isBlank()) {
            throw new IllegalArgumentException("Assignee is required");
        }
        var results = store.findCases(type, status, normalizedAssignee);
        var lookup = loadCaseLookup(results);
        return results.stream()
                .map(result -> toCaseView(result, lookup))
                .toList();
    }

    @Override
    public List<ReconciliationCaseProgress> findCaseProgresses() {
        return store.findCaseProgresses();
    }

    @Override
    public ReconciliationCaseDetailsView getResult(UUID resultId) {
        requireResultId(resultId);
        var result = store.getResult(resultId);
        var lookup = loadCaseLookup(List.of(result));
        var timeline = new ArrayList<>(store.findCaseEvents(resultId));
        Collections.reverse(timeline);
        return new ReconciliationCaseDetailsView(toCaseView(result, lookup), timeline);
    }

    @Override
    public ReconciliationOperationsSummary getOperationsSummary() {
        var latestBatch = store.findLatestCompletedBatch().orElse(null);
        Double matchRate = null;
        if (latestBatch != null) {
            int total = latestBatch.matchedRows() + latestBatch.differenceRows();
            if (total > 0) {
                matchRate = latestBatch.matchedRows() * 100.0 / total;
            }
        }
        return new ReconciliationOperationsSummary(
                latestBatch == null ? null : latestBatch.id(),
                matchRate,
                store.countResults(ResolutionStatus.OPEN),
                store.countResults(ResolutionStatus.CLAIMED),
                store.countFailedRuns());
    }

    private CaseLookup loadCaseLookup(List<ReconciliationResultEntity> results) {
        var batches = new HashMap<UUID, ReconciliationBatchView>();
        var entries = new HashMap<UUID, ReconciliationMatcher.StatementEntrySnapshot>();
        var payments = new HashMap<UUID, PaymentView>();
        var batchIds = results.stream().map(ReconciliationResultEntity::batchId).collect(java.util.stream.Collectors.toSet());
        for (UUID batchId : batchIds) {
            var batch = store.getBatch(batchId);
            batches.put(batchId, batch);
            store.findStatementEntries(batchId).forEach(entry -> entries.put(entry.id(), entry));
            findPayments(batch).forEach(payment -> payments.put(payment.id(), payment));
        }
        var resolutions = store.findResolutions(results.stream()
                .map(ReconciliationResultEntity::id)
                .collect(java.util.stream.Collectors.toSet()));
        return new CaseLookup(batches, entries, payments, resolutions);
    }

    private ReconciliationCaseView toCaseView(ReconciliationResultEntity result, CaseLookup lookup) {
        var batch = lookup.batches().get(result.batchId());
        var view = toResultView(result, lookup.entries(), lookup.payments(),
                lookup.resolutions().get(result.id()));
        Long difference = view.channelAmountCents() == null || view.internalAmountCents() == null
                ? null
                : view.internalAmountCents() - view.channelAmountCents();
        return new ReconciliationCaseView(
                view.id(), view.batchId(), batch.fileName(), view.statementEntryId(), view.paymentId(),
                view.channelTransactionId(), view.channelAmountCents(), view.internalAmountCents(),
                difference, view.occurredAt(), view.resultType(), view.resolutionStatus(),
                view.assignedTo(), view.claimedAt(),
                lookup.resolutions().get(result.id()) == null
                        ? null : lookup.resolutions().get(result.id()).resolutionCode(),
                view.resolutionNote(), view.resolvedBy(), view.resolvedAt());
    }

    private record CaseLookup(
            Map<UUID, ReconciliationBatchView> batches,
            Map<UUID, ReconciliationMatcher.StatementEntrySnapshot> entries,
            Map<UUID, PaymentView> payments,
            Map<UUID, ReconciliationResolutionEntity> resolutions) {}

    private ReconciliationResultView toResultView(ReconciliationResultEntity result) {
        var batch = store.getBatch(result.batchId());
        var entries = store.findStatementEntries(result.batchId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReconciliationMatcher.StatementEntrySnapshot::id, Function.identity()));
        var payments = findPayments(batch).stream()
                .collect(java.util.stream.Collectors.toMap(PaymentView::id, Function.identity()));
        return toResultView(result, entries, payments);
    }

    private ReconciliationResultView toResultView(
            ReconciliationResultEntity result,
            Map<UUID, ReconciliationMatcher.StatementEntrySnapshot> entries,
            Map<UUID, PaymentView> payments) {
        return toResultView(result, entries, payments, store.findResolution(result.id()).orElse(null));
    }

    private ReconciliationResultView toResultView(
            ReconciliationResultEntity result,
            Map<UUID, ReconciliationMatcher.StatementEntrySnapshot> entries,
            Map<UUID, PaymentView> payments,
            ReconciliationResolutionEntity resolution) {
        var entry = result.statementEntryId() == null ? null : entries.get(result.statementEntryId());
        var payment = result.paymentId() == null ? null : payments.get(result.paymentId());
        return new ReconciliationResultView(
                result.id(),
                result.batchId(),
                result.statementEntryId(),
                result.paymentId(),
                entry == null ? null : entry.channelTransactionId(),
                entry == null ? null : entry.amountCents(),
                payment == null ? null : payment.amountCents(),
                entry != null ? entry.occurredAt() : payment == null ? null : payment.occurredAt(),
                result.resultType(),
                result.resolutionStatus(),
                result.assignedTo(),
                result.claimedAt(),
                resolution == null ? null : resolution.note(),
                resolution == null ? null : resolution.operator(),
                resolution == null ? null : resolution.createdAt());
    }

    private static void requireResultId(UUID resultId) {
        if (resultId == null) {
            throw new IllegalArgumentException("Result id is required");
        }
    }

    private List<PaymentView> findPayments(ReconciliationBatchView batch) {
        if (batch.status() == io.github.user32694.ledgerplatform.reconciliation.BatchStatus.IMPORT_FAILED
                || batch.periodStart() == null
                || batch.periodEnd() == null) {
            return List.of();
        }
        return paymentsApi.findSucceededTopUps(batch.queryStart(), batch.queryEnd());
    }

}
