package io.github.user32694.ledgerplatform.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRuleVersionView(
        UUID id,
        UUID ruleId,
        RuleScopeType sourceScope,
        String channelCode,
        int versionNumber,
        RuleVersionStatus status,
        long amountToleranceCents,
        int queryWindowHours,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt) {}
