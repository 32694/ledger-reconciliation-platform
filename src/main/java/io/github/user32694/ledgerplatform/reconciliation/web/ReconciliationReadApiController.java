package io.github.user32694.ledgerplatform.reconciliation.web;

import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditEventView;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import io.github.user32694.ledgerplatform.ledger.LedgerTransactionDetailsView;
import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationEvidenceView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 面向 Agent 等外部消费者的只读证据 API；本控制器不包含任何写入映射。 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationReadApiController {
    private final ReconciliationApi reconciliationApi;
    private final PaymentsApi paymentsApi;
    private final LedgerApi ledgerApi;
    private final AuditApi auditApi;

    public ReconciliationReadApiController(
            ReconciliationApi reconciliationApi,
            PaymentsApi paymentsApi,
            LedgerApi ledgerApi,
            AuditApi auditApi) {
        this.reconciliationApi = reconciliationApi;
        this.paymentsApi = paymentsApi;
        this.ledgerApi = ledgerApi;
        this.auditApi = auditApi;
    }

    /** 查询完整案件证据包：案件、支付、账本、案件时间线和系统审计事件。 */
    @GetMapping("/results/{resultId}/evidence")
    public ReconciliationEvidenceView evidence(@PathVariable UUID resultId) {
        try {
            return reconciliationApi.getEvidence(resultId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reconciliation result not found");
        }
    }

    /** 查询一笔支付的只读视图。 */
    @GetMapping("/payments/{paymentId}")
    public PaymentView payment(@PathVariable UUID paymentId) {
        try {
            return paymentsApi.get(paymentId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
    }

    /** 按账本业务引用查询完整不可变分录。 */
    @GetMapping("/ledger/transactions/{businessReference}")
    public LedgerTransactionDetailsView ledger(@PathVariable String businessReference) {
        return ledgerApi.findTransactionByBusinessReference(businessReference)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Ledger transaction not found"));
    }

    /** 查询某个业务聚合的只追加审计轨迹。 */
    @GetMapping("/audit/{aggregateId}")
    public List<AuditEventView> audit(@PathVariable String aggregateId) {
        return auditApi.findByAggregateId(aggregateId);
    }

    /** 按批次读取异常案件，供外部系统发现待处理数据。 */
    @GetMapping("/cases")
    public List<?> cases(
            @RequestParam(required = false) String resultType,
            @RequestParam(required = false) String resolutionStatus,
            @RequestParam(required = false) String assignee) {
        try {
            var type = resultType == null ? null
                    : io.github.user32694.ledgerplatform.reconciliation.ResultType.valueOf(resultType);
            var status = resolutionStatus == null ? null
                    : io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus.valueOf(resolutionStatus);
            return reconciliationApi.findCases(type, status, assignee);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reconciliation filter");
        }
    }
}
