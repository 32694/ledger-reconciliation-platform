package io.github.user32694.ledgerplatform.reconciliation;

import java.util.List;
import java.util.UUID;

public interface ReconciliationApi {
    ReconciliationBatchView importStatement(StatementUpload upload);

    List<ReconciliationBatchView> findBatches();

    ReconciliationBatchView getBatch(UUID batchId);

    ReconciliationBatchView run(UUID batchId);

    ReconciliationRunView startRun(UUID batchId, String operator);

    List<ReconciliationRunView> findRuns(UUID batchId);

    List<ReconciliationResultView> findResults(
            UUID batchId, ResultType resultType, ResolutionStatus resolutionStatus);

    ReconciliationResultView claim(UUID resultId, String operator);

    ReconciliationResultView release(UUID resultId, String operator);

    ReconciliationResultView resolve(
            UUID resultId, ResolutionCode resolutionCode, String note, String operator);

    ReconciliationResultView resolve(UUID resultId, String note, String operator);

    List<ReconciliationCaseEventView> findCaseEvents(UUID resultId);
}
