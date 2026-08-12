package io.github.user32694.ledgerplatform.payments;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 支付模块公开端口；对外隐藏支付指令、状态推进和账本实现细节。 */
public interface PaymentsApi {
    /** 提交一笔模拟充值，并返回最终支付视图。 */
    PaymentView topUp(TopUpCommand command);
    /** 在两个客户钱包之间提交一笔转账。 */
    PaymentView transfer(TransferCommand command);
    /** 对成功充值执行退款，或对成功转账执行冲正。 */
    PaymentView reverse(ReversePaymentCommand command);
    /** 查询单笔支付及其当前状态。 */
    PaymentView get(UUID paymentId);
    /** 查询原支付当前是否已有活动中的反向支付。 */
    Optional<PaymentView> findActiveReverse(UUID originalPaymentId);
    /** 查询最近支付，供管理页面展示。 */
    List<PaymentView> findRecent(int limit);
    /** 查询时间范围内成功的充值，供对账读取内部事实。 */
    List<PaymentView> findSucceededTopUps(Instant fromInclusive, Instant toInclusive);
    /** 按渠道流水批量查询成功充值，并拒绝重复渠道流水。 */
    Map<String, PaymentView> findSucceededTopUpsByReferences(
            Set<String> references, Instant fromInclusive, Instant toInclusive);
    /** 使用时间和 UUID 组成的 keyset cursor 分页读取成功充值。 */
    PaymentPage findSucceededTopUpsAfter(
            Instant fromInclusive, Instant toInclusive, PaymentPageCursor after, int limit);
    /** 统计时间范围内成功充值数量。 */
    long countSucceededTopUps(Instant fromInclusive, Instant toInclusive);
}
