package io.github.user32694.ledgerplatform.reconciliation;

import java.util.List;
import java.util.UUID;

public interface ReconciliationRulesApi {
    List<ReconciliationChannelView> findChannels(boolean includeInactive);

    ReconciliationChannelView setChannelActive(String channelCode, boolean active, String operator);

    List<ReconciliationRuleView> findRules();

    ReconciliationRuleView getRule(UUID ruleId);

    ReconciliationRuleVersionView saveDraft(
            UUID ruleId, ReconciliationRuleDraftCommand command);

    ReconciliationRuleVersionView publish(UUID ruleId, String operator);

    ReconciliationRuleVersionView publish(
            UUID ruleId,
            UUID expectedDraftId,
            long expectedAmountToleranceCents,
            int expectedQueryWindowHours,
            String operator);

    List<ReconciliationRuleVersionView> findVersions(UUID ruleId);

    ReconciliationRuleVersionView resolvePublishedVersion(String channelCode);
}
