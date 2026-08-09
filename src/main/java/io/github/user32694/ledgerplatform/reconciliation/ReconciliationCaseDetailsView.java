package io.github.user32694.ledgerplatform.reconciliation;

import java.util.List;

public record ReconciliationCaseDetailsView(
        ReconciliationCaseView caseView,
        List<ReconciliationCaseEventView> timeline) {
    public ReconciliationCaseDetailsView {
        timeline = List.copyOf(timeline);
    }
}
