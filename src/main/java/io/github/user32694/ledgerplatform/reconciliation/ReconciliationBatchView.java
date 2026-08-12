package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 对账批次只读视图，包含规则快照、文件摘要、时间范围和运行统计。 */
public record ReconciliationBatchView(
        UUID id,
        String sourceType,
        String fileName,
        String fileSha256,
        String channelCode,
        String channelDisplayName,
        UUID ruleVersionId,
        int ruleVersionNumber,
        long amountToleranceCents,
        int queryWindowHours,
        Instant periodStart,
        Instant periodEnd,
        BatchStatus status,
        int totalRows,
        int matchedRows,
        int differenceRows,
        String errorMessage,
        String createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
    public Instant queryStart() {
        // 渠道文件的业务期间向前扩展规则配置的查询窗口，覆盖延迟入账。
        requireQueryable();
        return periodStart.minus(queryWindowHours, ChronoUnit.HOURS);
    }

    public Instant queryEnd() {
        // 与 queryStart 对称向后扩展，保证对账不会漏掉时间边界附近的支付。
        requireQueryable();
        return periodEnd.plus(queryWindowHours, ChronoUnit.HOURS);
    }

    private void requireQueryable() {
        if (status == BatchStatus.IMPORT_FAILED || periodStart == null || periodEnd == null) {
            throw new IllegalStateException("Batch has no successful import period");
        }
    }
}
