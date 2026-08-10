package io.github.user32694.ledgerplatform.reconciliation;

import java.util.UUID;

public record ReconciliationRuleView(
        UUID id,
        RuleScopeType scopeType,
        String channelCode,
        String channelDisplayName,
        ReconciliationRuleVersionView activeVersion,
        ReconciliationRuleVersionView draft,
        long version) {}
