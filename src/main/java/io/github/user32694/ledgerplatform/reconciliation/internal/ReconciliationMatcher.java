package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 对账输入的轻量快照模型；真正的匹配规则由 Batch processor 使用规则快照执行。 */
final class ReconciliationMatcher {
    record StatementEntrySnapshot(
            UUID id, int lineNumber, String channelTransactionId, long amountCents, Instant occurredAt) {}
}
