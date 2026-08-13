package io.github.user32694.ledgerplatform.reconciliation;

import io.github.user32694.ledgerplatform.audit.AuditEventView;
import io.github.user32694.ledgerplatform.ledger.LedgerTransactionDetailsView;
import io.github.user32694.ledgerplatform.payments.PaymentView;
import java.util.List;

/** Agent 或其他只读消费者使用的完整对账证据包。 */
public record ReconciliationEvidenceView(
        ReconciliationCaseView caseView,
        PaymentView payment,
        LedgerTransactionDetailsView ledgerTransaction,
        List<ReconciliationCaseEventView> caseEvents,
        List<AuditEventView> auditEvents) {
    public ReconciliationEvidenceView {
        caseEvents = List.copyOf(caseEvents);
        auditEvents = List.copyOf(auditEvents);
    }
}
