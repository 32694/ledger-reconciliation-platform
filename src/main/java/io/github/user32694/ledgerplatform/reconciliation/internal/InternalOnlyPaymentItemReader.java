package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.payments.PaymentPageCursor;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

public class InternalOnlyPaymentItemReader implements ItemStreamReader<ReconciliationWorkItem> {
    private static final String CURSOR_TIME = "cursorTime";
    private static final String CURSOR_ID = "cursorId";

    private final UUID runId;
    private final ReconciliationStore store;
    private final PaymentsApi paymentsApi;
    private final ArrayDeque<Candidate> bufferedPayments = new ArrayDeque<>();

    private PaymentPageCursor pageCursor;
    private PaymentPageCursor lastReturnedCursor;

    public InternalOnlyPaymentItemReader(UUID runId, ReconciliationStore store, PaymentsApi paymentsApi) {
        this.runId = runId;
        this.store = store;
        this.paymentsApi = paymentsApi;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        // 内部单边扫描使用 (occurredAt, id) 组成稳定游标，避免相同时间戳导致重复或漏读。
        pageCursor = executionContext.containsKey(CURSOR_TIME)
                ? new PaymentPageCursor(
                        Instant.parse(executionContext.getString(CURSOR_TIME)),
                        UUID.fromString(executionContext.getString(CURSOR_ID)))
                : null;
        lastReturnedCursor = pageCursor;
        bufferedPayments.clear();
    }

    @Override
    public ReconciliationWorkItem read() {
        if (bufferedPayments.isEmpty()) {
            loadPage();
        }
        var candidate = bufferedPayments.pollFirst();
        if (candidate == null) {
            return null;
        }
        lastReturnedCursor = candidate.cursor();
        return candidate.item();
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // 保存最后一个已返回且已提交的支付游标，失败恢复时从该位置之后继续。
        if (lastReturnedCursor == null) {
            return;
        }
        executionContext.putString(CURSOR_TIME, lastReturnedCursor.completedAt().toString());
        executionContext.putString(CURSOR_ID, lastReturnedCursor.id().toString());
    }

    @Override
    public void close() {
        bufferedPayments.clear();
    }

    private void loadPage() {
        var batch = store.getBatchForRun(runId);
        var page = paymentsApi.findSucceededTopUpsAfter(
                batch.queryStart(), batch.queryEnd(), pageCursor, 500);
        if (page.payments().isEmpty()) {
            return;
        }
        var consumed = store.findConsumedPaymentIds(
                runId, page.payments().stream().map(payment -> payment.id()).collect(java.util.stream.Collectors.toSet()));
        // 已在渠道侧匹配过的支付不能再次生成 INTERNAL_ONLY，消费集合由 run 维度隔离。
        for (var payment : page.payments()) {
            bufferedPayments.addLast(new Candidate(
                    new ReconciliationWorkItem.Payment(
                            payment.id(), payment.amountCents(), consumed.contains(payment.id())),
                    new PaymentPageCursor(payment.occurredAt(), payment.id())));
        }
        pageCursor = page.nextCursor();
    }

    private record Candidate(ReconciliationWorkItem.Payment item, PaymentPageCursor cursor) {}
}
