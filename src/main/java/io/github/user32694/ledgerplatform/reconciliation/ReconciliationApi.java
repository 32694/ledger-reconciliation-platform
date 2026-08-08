package io.github.user32694.ledgerplatform.reconciliation;

import java.util.List;
import java.util.UUID;

public interface ReconciliationApi {
    ReconciliationBatchView importStatement(StatementUpload upload);

    List<ReconciliationBatchView> findBatches();

    ReconciliationBatchView getBatch(UUID batchId);

    ReconciliationBatchView run(UUID batchId);

    List<ReconciliationResultView> findResults(
            UUID batchId, ResultType resultType, ResolutionStatus resolutionStatus);

    ReconciliationResultView resolve(UUID resultId, String note, String operator);
}
