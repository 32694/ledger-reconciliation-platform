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
        // Processor 只负责把一个输入快照转换成一个结果，不直接访问数据库。
        // 这样匹配规则可以独立测试，数据库写入则统一放到 Writer 的事务中完成。
        return matcher.process(item, amountToleranceCents);
    }
}
