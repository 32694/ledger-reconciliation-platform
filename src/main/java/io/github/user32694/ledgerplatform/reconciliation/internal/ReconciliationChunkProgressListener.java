package io.github.user32694.ledgerplatform.reconciliation.internal;

import java.util.UUID;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.ChunkListenerSupport;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

@Component
class ReconciliationChunkProgressListener extends ChunkListenerSupport {
    private final ReconciliationStore store;

    ReconciliationChunkProgressListener(ReconciliationStore store) {
        this.store = store;
    }

    @Override
    public void afterChunk(ChunkContext context) {
        StepExecution stepExecution = context.getStepContext().getStepExecution();
        String runId = stepExecution.getJobParameters().getString("runId");
        if (runId != null) {
            store.recordChunkProgress(
                    UUID.fromString(runId),
                    stepExecution.getStepName(),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, stepExecution.getReadCount())));
        }
    }
}
