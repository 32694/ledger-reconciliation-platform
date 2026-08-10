package io.github.user32694.ledgerplatform.reconciliation.internal;

import org.springframework.batch.item.ItemProcessor;

public class ReconciliationWorkItemProcessor
        implements ItemProcessor<ReconciliationWorkItem, ReconciliationWorkResult> {
    private final ReconciliationRuleMatcher matcher;
    private final long amountToleranceCents;

    public ReconciliationWorkItemProcessor(ReconciliationRuleMatcher matcher, long amountToleranceCents) {
        this.matcher = matcher;
        this.amountToleranceCents = amountToleranceCents;
    }

    @Override
    public ReconciliationWorkResult process(ReconciliationWorkItem item) {
        return matcher.process(item, amountToleranceCents);
    }
}
