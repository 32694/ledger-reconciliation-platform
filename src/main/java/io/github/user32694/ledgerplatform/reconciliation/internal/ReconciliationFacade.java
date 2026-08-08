package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationFacade implements ReconciliationApi {
    private final ReconciliationImportService importService;
    private final ReconciliationStore store;

    public ReconciliationFacade(
            ReconciliationImportService importService, ReconciliationStore store) {
        this.importService = importService;
        this.store = store;
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

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
