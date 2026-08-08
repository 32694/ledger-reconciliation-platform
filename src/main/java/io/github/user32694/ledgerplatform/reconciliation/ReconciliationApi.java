package io.github.user32694.ledgerplatform.reconciliation;

import java.util.List;
import java.util.UUID;

public interface ReconciliationApi {
    ReconciliationBatchView importStatement(StatementUpload upload);

    List<ReconciliationBatchView> findBatches();

    ReconciliationBatchView getBatch(UUID batchId);
}
