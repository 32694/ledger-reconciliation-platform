package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.UUID;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

final class ReconciliationWorkItemWriter implements ItemWriter<ReconciliationWorkResult> {
    private final UUID runId;
    private final UUID batchId;
    private final ReconciliationStore store;

    ReconciliationWorkItemWriter(UUID runId, UUID batchId, ReconciliationStore store) {
        this.runId = runId;
        this.batchId = batchId;
        this.store = store;
    }

    @Override
    public void write(Chunk<? extends ReconciliationWorkResult> chunk) {
        store.writeWorkResults(runId, batchId, java.util.List.copyOf(chunk.getItems()));
    }
}
