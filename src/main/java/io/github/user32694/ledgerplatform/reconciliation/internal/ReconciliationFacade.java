package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationResultView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationFacade implements ReconciliationApi {
    private final ReconciliationImportService importService;
    private final ReconciliationStore store;
    private final ReconciliationRunner runner;

    public ReconciliationFacade(
            ReconciliationImportService importService,
            ReconciliationStore store,
            ReconciliationRunner runner) {
        this.importService = importService;
        this.store = store;
        this.runner = runner;
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
    public List<ReconciliationResultView> findResults(
            UUID batchId, ResultType resultType, ResolutionStatus resolutionStatus) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch id is required");
        }
        return store.findResults(batchId).stream()
                .filter(result -> resultType == null || result.resultType() == resultType)
                .filter(result -> resolutionStatus == null || result.resolutionStatus() == resolutionStatus)
                .map(result -> new ReconciliationResultView(
                        result.id(),
                        result.batchId(),
                        result.statementEntryId(),
                        result.paymentId(),
                        null,
                        null,
                        null,
                        null,
                        result.resultType(),
                        result.resolutionStatus(),
                        null,
                        null,
                        null))
                .toList();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
