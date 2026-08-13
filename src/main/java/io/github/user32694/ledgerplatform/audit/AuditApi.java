package io.github.user32694.ledgerplatform.audit;

import java.util.List;

/** 审计模块公开端口；调用方只能追加和查询，不能修改或删除历史事件。 */
public interface AuditApi {
    /** 追加一条带业务引用的审计事件。 */
    AuditEventView record(AuditCommand command);

    /** 按动作、结果和数量筛选最近审计事件。 */
    List<AuditEventView> findRecent(AuditAction action, AuditOutcome outcome, int limit);

    /** 按聚合根 ID 查询完整审计轨迹，供只读证据 API 使用。 */
    List<AuditEventView> findByAggregateId(String aggregateId);
}
