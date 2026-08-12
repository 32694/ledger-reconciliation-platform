package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.UUID;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

public class ReconciliationWorkItemWriter implements ItemWriter<ReconciliationWorkResult> {
    private final UUID runId;
    private final UUID batchId;
    private final ReconciliationStore store;

    public ReconciliationWorkItemWriter(UUID runId, UUID batchId, ReconciliationStore store) {
        this.runId = runId;
        this.batchId = batchId;
        this.store = store;
    }

    @Override
    public void write(Chunk<? extends ReconciliationWorkResult> chunk) {
        // Spring Batch 会在一个 chunk 事务中调用 Writer；批量落库可以减少逐条提交的开销。
        // work 表先保存中间结果，最终由 finalize 步骤一次性提升为正式结果，避免半批次可见。
        store.writeWorkResults(runId, batchId, java.util.List.copyOf(chunk.getItems()));
    }
}
