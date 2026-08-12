package io.github.user32694.ledgerplatform.reconciliation;

import java.util.List;
import java.util.UUID;

/** 对账运营模块公开端口，覆盖导入、运行、异常处理和运营统计。 */
public interface ReconciliationApi {
    /** 导入渠道 CSV，并按文件哈希保证重复上传幂等。 */
    ReconciliationBatchView importStatement(StatementUpload upload);

    List<ReconciliationBatchView> findBatches();

    ReconciliationBatchView getBatch(UUID batchId);

    /** 为已导入批次创建 Spring Batch 运行。 */
    ReconciliationRunView startRun(UUID batchId, String operator);

    /** 从失败 checkpoint 恢复原运行，避免重新处理已提交块。 */
    ReconciliationRunView restartRun(UUID runId, String operator);

    List<ReconciliationRunView> findRuns(UUID batchId);

    List<ReconciliationResultView> findResults(
            UUID batchId, ResultType resultType, ResolutionStatus resolutionStatus);

    /** 认领差异案件，防止多个运营员同时处理同一案件。 */
    ReconciliationResultView claim(UUID resultId, String operator);

    ReconciliationResultView release(UUID resultId, String operator);

    /** 记录人工解决结论；不会改写原始支付或渠道事实。 */
    ReconciliationResultView resolve(
            UUID resultId, ResolutionCode resolutionCode, String note, String operator);

    List<ReconciliationCaseEventView> findCaseEvents(UUID resultId);

    List<ReconciliationCaseView> findCases(
            ResultType type, ResolutionStatus status, String assignee);

    List<ReconciliationCaseProgress> findCaseProgresses();

    ReconciliationCaseDetailsView getResult(UUID resultId);

    ReconciliationOperationsSummary getOperationsSummary();
}
