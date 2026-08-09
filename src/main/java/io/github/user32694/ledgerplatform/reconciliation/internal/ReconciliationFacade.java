package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationResultView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationFacade implements ReconciliationApi {
    private final ReconciliationImportService importService;
    private final ReconciliationStore store;
    private final ReconciliationRunner runner;
    private final PaymentsApi paymentsApi;

    public ReconciliationFacade(
            ReconciliationImportService importService,
            ReconciliationStore store,
            ReconciliationRunner runner,
            PaymentsApi paymentsApi) {
        this.importService = importService;
        this.store = store;
        this.runner = runner;
        this.paymentsApi = paymentsApi;
    }

    @Override
    public ReconciliationBatchView importStatement(StatementUpload upload) {
        try {
            return importService.importStatement(upload);
        } catch (IllegalArgumentException exception) {
            if (upload == null || upload.fileName() == null || upload.operator() == null) {
                throw exception;
            }
            String hash = java.util.HexFormat.of().formatHex(
                    sha256(upload.content() == null ? new byte[0] : upload.content()));
            return store.findByHash(hash)
                    .map(ReconciliationBatchEntity::toView)
                    .orElseThrow(() -> exception);
        }
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
    public ReconciliationBatchView run(UUID batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        return runner.run(batchId);
    }

    @Override
    public ReconciliationRunView startRun(UUID batchId, String operator) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("Operator is required");
        }
        return store.queueRun(batchId, operator.strip());
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
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        var batch = store.getBatch(batchId);
        var entries = store.findStatementEntries(batchId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReconciliationMatcher.StatementEntrySnapshot::id, Function.identity()));
        var payments = batch.periodStart() == null
                ? List.<PaymentView>of()
                : paymentsApi.findSucceededTopUps(batch.periodStart(), batch.periodEnd());
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
    public ReconciliationResultView resolve(UUID resultId, String note, String operator) {
        if (resultId == null) {
            throw new IllegalArgumentException("Result id is required");
        }
        var result = store.resolveResult(resultId, note, operator);
        var batch = store.getBatch(result.batchId());
        var entries = store.findStatementEntries(result.batchId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReconciliationMatcher.StatementEntrySnapshot::id, Function.identity()));
        var payments = paymentsApi.findSucceededTopUps(batch.periodStart(), batch.periodEnd()).stream()
                .collect(java.util.stream.Collectors.toMap(PaymentView::id, Function.identity()));
        return toResultView(result, entries, payments);
    }

    private ReconciliationResultView toResultView(
            ReconciliationResultEntity result,
            Map<UUID, ReconciliationMatcher.StatementEntrySnapshot> entries,
            Map<UUID, PaymentView> payments) {
        var resolution = store.findResolution(result.id());
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
                resolution.map(ReconciliationResolutionEntity::note).orElse(null),
                resolution.map(ReconciliationResolutionEntity::operator).orElse(null),
                resolution.map(ReconciliationResolutionEntity::createdAt).orElse(null));
    }

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
