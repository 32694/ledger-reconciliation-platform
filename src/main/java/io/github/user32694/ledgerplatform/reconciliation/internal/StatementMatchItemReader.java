package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

public class StatementMatchItemReader implements ItemStreamReader<ReconciliationWorkItem> {
    private static final String LAST_COMMITTED_LINE_NUMBER = "lastCommittedLineNumber";

    private final UUID runId;
    private final ReconciliationStore store;
    private final ChannelStatementEntryRepository entryRepository;
    private final PaymentsApi paymentsApi;
    private final ArrayDeque<ChannelStatementEntryEntity> bufferedEntries = new ArrayDeque<>();

    private int pageAfterLineNumber;
    private int lastReturnedLineNumber;

    public StatementMatchItemReader(
            UUID runId,
            ReconciliationStore store,
            ChannelStatementEntryRepository entryRepository,
            PaymentsApi paymentsApi) {
        this.runId = runId;
        this.store = store;
        this.entryRepository = entryRepository;
        this.paymentsApi = paymentsApi;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        // ExecutionContext 是 Spring Batch 的断点：重启时从上次成功提交的行号继续。
        pageAfterLineNumber = executionContext.getInt(LAST_COMMITTED_LINE_NUMBER, 0);
        lastReturnedLineNumber = pageAfterLineNumber;
        bufferedEntries.clear();
    }

    @Override
    public ReconciliationWorkItem read() {
        // 先按渠道账单行号分页，再用交易号批量查询内部支付，避免每行一次数据库查询（N+1）。
        if (bufferedEntries.isEmpty()) {
            loadPage();
        }
        var entry = bufferedEntries.pollFirst();
        if (entry == null) {
            return null;
        }
        lastReturnedLineNumber = entry.lineNumber();
        return new ReconciliationWorkItem.Statement(
                entry.id(),
                entry.amountCents(),
                Optional.ofNullable(currentPayments.get(entry.channelTransactionId()))
                        .map(payment -> new ReconciliationWorkItem.Payment(
                                payment.id(), payment.amountCents(), false)));
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // 只有 chunk 成功提交后才会调用 update，因此记录的行号不会越过未提交数据。
        executionContext.putInt(LAST_COMMITTED_LINE_NUMBER, lastReturnedLineNumber);
    }

    @Override
    public void close() {
        bufferedEntries.clear();
    }

    private java.util.Map<String, io.github.user32694.ledgerplatform.payments.PaymentView> currentPayments =
            java.util.Map.of();

    private void loadPage() {
        var batch = store.getBatchForRun(runId);
        var page = entryRepository.findTop500ByBatchIdAndLineNumberGreaterThanOrderByLineNumberAsc(
                batch.id(), pageAfterLineNumber);
        if (page.isEmpty()) {
            currentPayments = java.util.Map.of();
            return;
        }
        currentPayments = paymentsApi.findSucceededTopUpsByReferences(
                page.stream().map(ChannelStatementEntryEntity::channelTransactionId)
                        .collect(java.util.stream.Collectors.toSet()),
                batch.queryStart(), batch.queryEnd());
        // 当前页的支付快照只在内存中使用；下一页重新查询，控制单次作业的内存占用。
        bufferedEntries.addAll(page);
        pageAfterLineNumber = page.get(page.size() - 1).lineNumber();
    }
}
