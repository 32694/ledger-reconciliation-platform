package io.github.user32694.ledgerplatform.reconciliation.internal;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditCommand;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationChannelView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleDraftCommand;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleVersionView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRulesApi;
import io.github.user32694.ledgerplatform.reconciliation.RuleScopeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReconciliationRuleService implements ReconciliationRulesApi {
    private static final String LEGACY_SYNTHETIC = "LEGACY_SYNTHETIC";
    private static final String STALE_DRAFT_MESSAGE = "草稿已更新，请刷新后重新确认";

    private final ReconciliationChannelRepository channelRepository;
    private final ReconciliationRuleRepository ruleRepository;
    private final ReconciliationRuleVersionRepository versionRepository;
    private final AuditApi auditApi;

    ReconciliationRuleService(
            ReconciliationChannelRepository channelRepository,
            ReconciliationRuleRepository ruleRepository,
            ReconciliationRuleVersionRepository versionRepository,
            AuditApi auditApi) {
        this.channelRepository = channelRepository;
        this.ruleRepository = ruleRepository;
        this.versionRepository = versionRepository;
        this.auditApi = auditApi;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationChannelView> findChannels(boolean includeInactive) {
        var channels = includeInactive
                ? channelRepository.findAllByCodeNotOrderByCodeAsc(LEGACY_SYNTHETIC)
                : channelRepository.findAllByActiveTrueAndCodeNotOrderByCodeAsc(
                        LEGACY_SYNTHETIC);
        return channels.stream().map(ReconciliationChannelEntity::toView).toList();
    }

    @Override
    @Transactional
    public ReconciliationChannelView setChannelActive(
            String channelCode, boolean active, String operator) {
        String normalizedCode = requireChannelCode(channelCode);
        String normalizedOperator = requireOperator(operator);
        var channel = findSelectableChannel(normalizedCode);
        channel.setActive(active);
        channelRepository.flush();
        auditApi.record(new AuditCommand(
                normalizedOperator,
                AuditAction.RECONCILIATION_CHANNEL_STATUS_CHANGE,
                "RECONCILIATION_CHANNEL",
                channel.id().toString(),
                AuditOutcome.SUCCEEDED,
                active ? "对账渠道已启用" : "对账渠道已停用",
                channel.code()));
        return channel.toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationRuleView> findRules() {
        return ruleRepository.findAllByOrderByScopeTypeAscChannelIdAsc().stream()
                .map(this::toRuleView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationRuleView getRule(UUID ruleId) {
        return toRuleView(findRule(ruleId));
    }

    @Override
    @Transactional
    public ReconciliationRuleVersionView saveDraft(
            UUID ruleId, ReconciliationRuleDraftCommand command) {
        requireRuleId(ruleId);
        validate(command);
        String operator = requireOperator(command.operator());
        var rule = findRuleForUpdate(ruleId);
        Instant now = Instant.now();
        var draft = versionRepository.findDraftByRuleId(ruleId).orElseGet(() -> {
            var base = findDraftBase(rule);
            int nextVersionNumber = versionRepository
                            .findAllByRuleIdOrderByVersionNumberDesc(ruleId)
                            .stream()
                            .findFirst()
                            .map(version -> version.versionNumber() + 1)
                            .orElse(1);
            return ReconciliationRuleVersionEntity.draft(
                    ruleId,
                    nextVersionNumber,
                    base.amountToleranceCents(),
                    base.queryWindowHours(),
                    operator,
                    now);
        });
        draft.updateDraft(command.amountToleranceCents(), command.queryWindowHours());
        versionRepository.saveAndFlush(draft);
        auditApi.record(new AuditCommand(
                operator,
                AuditAction.RECONCILIATION_RULE_DRAFT_SAVE,
                "RECONCILIATION_RULE",
                rule.id().toString(),
                AuditOutcome.SUCCEEDED,
                "对账规则草稿已保存",
                draft.id().toString()));
        return toVersionView(draft, rule);
    }

    @Override
    @Transactional
    public ReconciliationRuleVersionView publish(UUID ruleId, String operator) {
        requireRuleId(ruleId);
        String normalizedOperator = requireOperator(operator);
        var rule = findRuleForUpdate(ruleId);
        var draft = versionRepository.findDraftByRuleId(ruleId)
                .orElseThrow(() -> new IllegalStateException("没有可发布的规则草稿"));
        return publish(rule, draft, normalizedOperator);
    }

    @Override
    @Transactional
    public ReconciliationRuleVersionView publish(
            UUID ruleId,
            UUID expectedDraftId,
            long expectedAmountToleranceCents,
            int expectedQueryWindowHours,
            String operator) {
        requireRuleId(ruleId);
        String normalizedOperator = requireOperator(operator);
        var rule = findRuleForUpdate(ruleId);
        var draft = versionRepository.findDraftByRuleId(ruleId)
                .orElseThrow(() -> new IllegalStateException(STALE_DRAFT_MESSAGE));
        if (!draft.id().equals(expectedDraftId)
                || draft.amountToleranceCents() != expectedAmountToleranceCents
                || draft.queryWindowHours() != expectedQueryWindowHours) {
            throw new IllegalStateException(STALE_DRAFT_MESSAGE);
        }
        return publish(rule, draft, normalizedOperator);
    }

    private ReconciliationRuleVersionView publish(
            ReconciliationRuleEntity rule,
            ReconciliationRuleVersionEntity draft,
            String normalizedOperator) {
        draft.publish(normalizedOperator, Instant.now());
        versionRepository.flush();
        rule.activate(draft.id());
        ruleRepository.flush();
        auditApi.record(new AuditCommand(
                normalizedOperator,
                AuditAction.RECONCILIATION_RULE_PUBLISH,
                "RECONCILIATION_RULE",
                rule.id().toString(),
                AuditOutcome.SUCCEEDED,
                "对账规则版本已发布",
                draft.id().toString()));
        return toVersionView(draft, rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationRuleVersionView> findVersions(UUID ruleId) {
        var rule = findRule(ruleId);
        return versionRepository.findAllByRuleIdOrderByVersionNumberDesc(ruleId).stream()
                .map(version -> toVersionView(version, rule))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationRuleVersionView resolvePublishedVersion(String channelCode) {
        var channel = findSelectableChannel(requireChannelCode(channelCode));
        if (!channel.active()) {
            throw new IllegalStateException("对账渠道未启用: " + channel.code());
        }
        var channelVersion = ruleRepository.findByChannelId(channel.id())
                .filter(rule -> rule.activeVersionId() != null)
                .map(rule -> toVersionView(findVersion(rule.activeVersionId()), rule));
        if (channelVersion.isPresent()) {
            return channelVersion.get();
        }
        var defaultRule = findDefaultRule();
        if (defaultRule.activeVersionId() == null) {
            throw new IllegalStateException("No published reconciliation rule");
        }
        return toVersionView(findVersion(defaultRule.activeVersionId()), defaultRule);
    }

    private ReconciliationRuleView toRuleView(ReconciliationRuleEntity rule) {
        ReconciliationChannelEntity channel = rule.channelId() == null
                ? null
                : channelRepository.findById(rule.channelId())
                        .orElseThrow(() -> new IllegalStateException("对账规则关联的渠道不存在"));
        var activeVersion = rule.activeVersionId() == null
                ? null
                : toVersionView(findVersion(rule.activeVersionId()), rule);
        var draft = versionRepository.findDraftByRuleId(rule.id())
                .map(version -> toVersionView(version, rule))
                .orElse(null);
        return new ReconciliationRuleView(
                rule.id(),
                rule.scopeType(),
                channel == null ? null : channel.code(),
                channel == null ? null : channel.displayName(),
                activeVersion,
                draft,
                rule.version());
    }

    private ReconciliationRuleVersionView toVersionView(
            ReconciliationRuleVersionEntity version, ReconciliationRuleEntity rule) {
        String channelCode = rule.channelId() == null
                ? null
                : channelRepository.findById(rule.channelId())
                        .map(ReconciliationChannelEntity::code)
                        .orElseThrow(() -> new IllegalStateException("对账规则关联的渠道不存在"));
        return new ReconciliationRuleVersionView(
                version.id(),
                version.ruleId(),
                rule.scopeType(),
                channelCode,
                version.versionNumber(),
                version.status(),
                version.amountToleranceCents(),
                version.queryWindowHours(),
                version.createdBy(),
                version.createdAt(),
                version.publishedBy(),
                version.publishedAt());
    }

    private ReconciliationRuleVersionEntity findDraftBase(ReconciliationRuleEntity rule) {
        if (rule.activeVersionId() != null) {
            return findVersion(rule.activeVersionId());
        }
        if (rule.scopeType() == RuleScopeType.CHANNEL) {
            var defaultRule = findDefaultRule();
            if (defaultRule.activeVersionId() != null) {
                return findVersion(defaultRule.activeVersionId());
            }
        }
        throw new IllegalStateException("No published reconciliation rule");
    }

    private ReconciliationChannelEntity findSelectableChannel(String channelCode) {
        var channel = channelRepository.findByCode(channelCode)
                .orElseThrow(() -> new IllegalArgumentException("对账渠道不存在: " + channelCode));
        if (LEGACY_SYNTHETIC.equals(channel.code())) {
            throw new IllegalArgumentException("对账渠道不存在: " + channelCode);
        }
        return channel;
    }

    private ReconciliationRuleEntity findRule(UUID ruleId) {
        requireRuleId(ruleId);
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("对账规则不存在: " + ruleId));
    }

    private ReconciliationRuleEntity findRuleForUpdate(UUID ruleId) {
        return ruleRepository.findByIdForUpdate(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("对账规则不存在: " + ruleId));
    }

    private ReconciliationRuleEntity findDefaultRule() {
        return ruleRepository.findDefault()
                .orElseThrow(() -> new IllegalStateException("No published reconciliation rule"));
    }

    private ReconciliationRuleVersionEntity findVersion(UUID versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("对账规则版本不存在: " + versionId));
    }

    private static void validate(ReconciliationRuleDraftCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("规则草稿不能为空");
        }
        if (command.amountToleranceCents() < 0) {
            throw new IllegalArgumentException("金额容差不能小于 0");
        }
        if (command.queryWindowHours() < 0 || command.queryWindowHours() > 168) {
            throw new IllegalArgumentException("查询窗口必须在 0 到 168 小时之间");
        }
    }

    private static void requireRuleId(UUID ruleId) {
        if (ruleId == null) {
            throw new IllegalArgumentException("规则编号不能为空");
        }
    }

    private static String requireChannelCode(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            throw new IllegalArgumentException("渠道编码不能为空");
        }
        String normalized = channelCode.strip();
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("渠道编码不能超过 32 个字符");
        }
        return normalized;
    }

    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("操作人不能为空");
        }
        String normalized = operator.strip();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("操作人不能超过 128 个字符");
        }
        return normalized;
    }
}
