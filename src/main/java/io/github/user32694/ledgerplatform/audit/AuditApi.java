package io.github.user32694.ledgerplatform.audit;

import java.util.List;

public interface AuditApi {
    AuditEventView record(AuditCommand command);

    List<AuditEventView> findRecent(AuditAction action, AuditOutcome outcome, int limit);
}
