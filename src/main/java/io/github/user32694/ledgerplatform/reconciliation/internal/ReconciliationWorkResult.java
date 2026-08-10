package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.util.UUID;

record ReconciliationWorkResult(
        UUID statementEntryId,
        UUID paymentId,
        ResultType resultType,
        ResolutionStatus resolutionStatus) {}
