package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import org.springframework.stereotype.Component;

@Component
final class ReconciliationRuleMatcher {
    ReconciliationWorkResult process(
            ReconciliationWorkItem item, long amountToleranceCents) {
        // 渠道账单和内部支付走同一个纯匹配器，差异类型最终由这里统一定义。
        if (item instanceof ReconciliationWorkItem.Statement statement) {
            return matchStatement(statement, amountToleranceCents);
        }
        var payment = (ReconciliationWorkItem.Payment) item;
        if (payment.consumed()) {
            // 渠道侧已经消费过该支付时跳过，防止同一内部支付被重复标记为单边。
            return null;
        }
        return result(null, payment.paymentId(), ResultType.INTERNAL_ONLY);
    }

    private static ReconciliationWorkResult matchStatement(
            ReconciliationWorkItem.Statement statement, long amountToleranceCents) {
        if (statement.exactPayment().isEmpty()) {
            return result(statement.statementEntryId(), null, ResultType.CHANNEL_ONLY);
        }
        var payment = statement.exactPayment().orElseThrow();
        long difference = Math.max(statement.amountCents(), payment.amountCents())
                - Math.min(statement.amountCents(), payment.amountCents());
        // 使用绝对差额与规则快照中的容差比较；金额单位始终是分，避免浮点误差。
        var type = difference <= amountToleranceCents
                ? ResultType.MATCHED
                : ResultType.AMOUNT_MISMATCH;
        return result(statement.statementEntryId(), payment.paymentId(), type);
    }

    private static ReconciliationWorkResult result(
            java.util.UUID statementEntryId,
            java.util.UUID paymentId,
            ResultType resultType) {
        var status = resultType == ResultType.MATCHED
                ? ResolutionStatus.NOT_REQUIRED
                : ResolutionStatus.OPEN;
        return new ReconciliationWorkResult(statementEntryId, paymentId, resultType, status);
    }
}
