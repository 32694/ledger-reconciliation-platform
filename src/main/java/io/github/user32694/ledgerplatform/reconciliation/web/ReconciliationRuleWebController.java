package io.github.user32694.ledgerplatform.reconciliation.web;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationChannelView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleDraftCommand;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleVersionView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRuleView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRulesApi;
import io.github.user32694.ledgerplatform.reconciliation.RuleScopeType;
import io.github.user32694.ledgerplatform.reconciliation.RuleVersionStatus;
import io.github.user32694.ledgerplatform.reconciliation.StaleReconciliationRuleDraftException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/reconciliation")
public class ReconciliationRuleWebController {
    private static final String RULES_URL = "/admin/reconciliation/rules";
    private static final String RULE_ERROR = "操作失败，请刷新页面后重试";

    private final ReconciliationRulesApi rulesApi;

    public ReconciliationRuleWebController(ReconciliationRulesApi rulesApi) {
        this.rulesApi = rulesApi;
    }

    @GetMapping("/rules")
    String list(Model model) {
        var rules = rulesApi.findRules();
        Map<String, ReconciliationChannelView> channels = rulesApi.findChannels(true).stream()
                .collect(Collectors.toMap(ReconciliationChannelView::code, Function.identity()));
        ReconciliationRuleVersionView defaultVersion = rules.stream()
                .filter(rule -> rule.scopeType() == RuleScopeType.DEFAULT)
                .map(ReconciliationRuleView::activeVersion)
                .findFirst()
                .orElse(null);
        var rows = rules.stream()
                .sorted(Comparator
                        .comparingInt((ReconciliationRuleView rule) ->
                                rule.scopeType() == RuleScopeType.DEFAULT ? 0 : 1)
                        .thenComparing(rule -> rule.channelDisplayName() == null
                                ? "" : rule.channelDisplayName()))
                .map(rule -> toRuleRow(rule, channels.get(rule.channelCode()), defaultVersion))
                .toList();

        model.addAttribute("rules", rows);
        model.addAttribute("activeNav", "reconciliation-rules");
        return "admin/reconciliation-rules";
    }

    @GetMapping("/rules/{ruleId}/edit")
    String edit(@PathVariable UUID ruleId, Model model) {
        ReconciliationRuleView rule = getRuleOrNotFound(ruleId);
        ReconciliationRuleVersionView effective = effectiveVersion(rule);
        ReconciliationRuleVersionView source = rule.draft() == null ? effective : rule.draft();
        addEditModel(
                model,
                rule,
                effective,
                DraftSnapshot.from(rule.draft()),
                source == null ? "" : amount(source.amountToleranceCents()),
                source == null ? "" : Integer.toString(source.queryWindowHours()),
                null,
                null,
                null);
        return "admin/reconciliation-rule-edit";
    }

    @PostMapping("/rules/{ruleId}/draft")
    String saveDraft(
            @PathVariable UUID ruleId,
            @RequestParam(required = false) String amountTolerance,
            @RequestParam(required = false) String queryWindowHours,
            @RequestParam(defaultValue = "false") boolean expectedDraftPresent,
            @RequestParam(required = false) UUID expectedDraftId,
            @RequestParam(required = false) Long expectedAmountToleranceCents,
            @RequestParam(required = false) Integer expectedQueryWindowHours,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes) {
        ReconciliationRuleView rule;
        try {
            rule = rulesApi.getRule(ruleId);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("ruleError", RULE_ERROR);
            return "redirect:" + RULES_URL;
        }
        var draftSnapshot = new DraftSnapshot(
                expectedDraftPresent,
                expectedDraftId,
                expectedAmountToleranceCents,
                expectedQueryWindowHours);

        DraftInput input = validateDraft(amountTolerance, queryWindowHours);
        if (!input.valid()) {
            addEditModel(
                    model,
                    rule,
                    effectiveVersion(rule),
                    draftSnapshot,
                    amountTolerance,
                    queryWindowHours,
                    input.amountError(),
                    input.windowError(),
                    null);
            return "admin/reconciliation-rule-edit";
        }

        try {
            rulesApi.saveDraft(
                    ruleId,
                    new ReconciliationRuleDraftCommand(
                            input.amountToleranceCents(), input.queryWindowHours(), operator(authentication)),
                    expectedDraftPresent,
                    expectedDraftId,
                    expectedAmountToleranceCents,
                    expectedQueryWindowHours);
        } catch (StaleReconciliationRuleDraftException exception) {
            redirectAttributes.addFlashAttribute(
                    "ruleError", "草稿已更新，请刷新后重新编辑");
            return "redirect:" + RULES_URL + "/" + ruleId + "/edit";
        } catch (IllegalArgumentException exception) {
            addEditModel(
                    model,
                    rule,
                    effectiveVersion(rule),
                    draftSnapshot,
                    amountTolerance,
                    queryWindowHours,
                    null,
                    null,
                    "草稿保存失败，请检查输入后重试");
            return "admin/reconciliation-rule-edit";
        }

        redirectAttributes.addFlashAttribute("ruleSuccess", "对账规则草稿已保存");
        return "redirect:" + RULES_URL + "/" + ruleId + "/edit";
    }

    @PostMapping("/rules/{ruleId}/publish")
    String publish(
            @PathVariable UUID ruleId,
            @RequestParam UUID expectedDraftId,
            @RequestParam long expectedAmountToleranceCents,
            @RequestParam int expectedQueryWindowHours,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            rulesApi.publish(
                    ruleId,
                    expectedDraftId,
                    expectedAmountToleranceCents,
                    expectedQueryWindowHours,
                    operator(authentication));
            redirectAttributes.addFlashAttribute("ruleSuccess", "对账规则版本已发布");
        } catch (StaleReconciliationRuleDraftException exception) {
            redirectAttributes.addFlashAttribute(
                    "ruleError", "草稿已更新，请刷新后重新确认");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("ruleError", "发布失败，请确认已保存待发布草稿");
        }
        return "redirect:" + RULES_URL;
    }

    @GetMapping("/rules/{ruleId}/history")
    String history(@PathVariable UUID ruleId, Model model) {
        ReconciliationRuleView rule = getRuleOrNotFound(ruleId);
        var versions = rulesApi.findVersions(ruleId).stream()
                .map(ReconciliationRuleWebController::toVersionRow)
                .toList();
        model.addAttribute("ruleTitle", ruleTitle(rule));
        model.addAttribute("versions", versions);
        model.addAttribute("activeNav", "reconciliation-rules");
        return "admin/reconciliation-rule-history";
    }

    @PostMapping("/channels/{channelCode}/status")
    String setChannelStatus(
            @PathVariable String channelCode,
            @RequestParam boolean active,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            ReconciliationChannelView channel =
                    rulesApi.setChannelActive(channelCode, active, operator(authentication));
            redirectAttributes.addFlashAttribute(
                    "ruleSuccess", channel.displayName() + (channel.active() ? "已启用" : "已停用"));
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("ruleError", RULE_ERROR);
        }
        return "redirect:" + RULES_URL;
    }

    private ReconciliationRuleView getRuleOrNotFound(UUID ruleId) {
        try {
            return rulesApi.getRule(ruleId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账规则不存在");
        }
    }

    private ReconciliationRuleVersionView effectiveVersion(ReconciliationRuleView rule) {
        if (rule.activeVersion() != null) {
            return rule.activeVersion();
        }
        return rulesApi.findRules().stream()
                .filter(candidate -> candidate.scopeType() == RuleScopeType.DEFAULT)
                .map(ReconciliationRuleView::activeVersion)
                .findFirst()
                .orElse(null);
    }

    private void addEditModel(
            Model model,
            ReconciliationRuleView rule,
            ReconciliationRuleVersionView effective,
            DraftSnapshot draftSnapshot,
            String amountTolerance,
            String queryWindowHours,
            String amountError,
            String windowError,
            String ruleError) {
        model.addAttribute("rule", rule);
        model.addAttribute("ruleTitle", ruleTitle(rule));
        model.addAttribute("scopeLabel", scopeLabel(rule));
        model.addAttribute("effectiveVersionLabel", effectiveVersionLabel(rule, effective));
        model.addAttribute("effectiveAmount", effective == null ? "--" : amount(effective.amountToleranceCents()));
        model.addAttribute("effectiveWindow", effective == null ? "--" : effective.queryWindowHours());
        model.addAttribute("amountTolerance", amountTolerance == null ? "" : amountTolerance);
        model.addAttribute("queryWindowHours", queryWindowHours == null ? "" : queryWindowHours);
        model.addAttribute("amountError", amountError);
        model.addAttribute("windowError", windowError);
        model.addAttribute("ruleError", ruleError);
        model.addAttribute("expectedDraftPresent", draftSnapshot.present());
        model.addAttribute("expectedDraftId", draftSnapshot.id());
        model.addAttribute("expectedAmountToleranceCents", draftSnapshot.amountToleranceCents());
        model.addAttribute("expectedQueryWindowHours", draftSnapshot.queryWindowHours());
        model.addAttribute("pendingSummary", rule.draft() == null ? null : pendingSummary(rule.draft()));
        model.addAttribute("activeNav", "reconciliation-rules");
    }

    private static RuleRow toRuleRow(
            ReconciliationRuleView rule,
            ReconciliationChannelView channel,
            ReconciliationRuleVersionView defaultVersion) {
        ReconciliationRuleVersionView effective =
                rule.activeVersion() == null ? defaultVersion : rule.activeVersion();
        return new RuleRow(
                rule.id(),
                ruleTitle(rule),
                scopeLabel(rule),
                effectiveVersionLabel(rule, effective),
                effective == null ? "--" : amount(effective.amountToleranceCents()) + " 元",
                effective == null ? "--" : effective.queryWindowHours() + " 小时",
                rule.draft() != null,
                rule.draft() == null ? "无待发布草稿" : "待发布草稿",
                rule.draft() == null ? null : pendingSummary(rule.draft()),
                rule.draft() == null ? null : rule.draft().id(),
                rule.draft() == null ? null : rule.draft().amountToleranceCents(),
                rule.draft() == null ? null : rule.draft().queryWindowHours(),
                channel == null ? null : channel.code(),
                channel == null ? null : channel.active(),
                channel == null ? "--" : channel.active() ? "启用" : "停用");
    }

    private static VersionRow toVersionRow(ReconciliationRuleVersionView version) {
        return new VersionRow(
                version.versionNumber(),
                version.status() == RuleVersionStatus.PUBLISHED ? "已发布" : "草稿",
                amount(version.amountToleranceCents()) + " 元",
                version.queryWindowHours() + " 小时",
                version.createdBy(),
                version.createdAt(),
                version.publishedBy(),
                version.publishedAt());
    }

    private static DraftInput validateDraft(String amountTolerance, String queryWindowHours) {
        Long cents = null;
        Integer hours = null;
        String amountError = null;
        String windowError = null;

        if (amountTolerance == null || amountTolerance.isBlank()) {
            amountError = "请输入有效的金额容差";
        } else {
            String raw = amountTolerance.strip();
            try {
                BigDecimal decimal = new BigDecimal(raw);
                if (decimal.scale() > 2) {
                    amountError = "金额容差最多保留两位小数";
                } else {
                    cents = new BigDecimal(raw).movePointRight(2).longValueExact();
                    if (cents < 0) {
                        amountError = "金额容差不能小于 0";
                        cents = null;
                    }
                }
            } catch (NumberFormatException exception) {
                amountError = "请输入有效的金额容差";
            } catch (ArithmeticException exception) {
                amountError = "金额容差超出支持范围";
            }
        }

        if (queryWindowHours == null || queryWindowHours.isBlank()) {
            windowError = "请输入查询窗口";
        } else {
            try {
                hours = Integer.valueOf(queryWindowHours.strip());
                if (hours < 0 || hours > 168) {
                    windowError = "查询窗口必须在 0 到 168 小时之间";
                    hours = null;
                }
            } catch (NumberFormatException exception) {
                windowError = "查询窗口必须是 0 到 168 之间的整数";
            }
        }
        return new DraftInput(cents, hours, amountError, windowError);
    }

    private static String operator(Authentication authentication) {
        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new IllegalArgumentException("操作人不能为空");
        }
        return authentication.getName();
    }

    private static String ruleTitle(ReconciliationRuleView rule) {
        return rule.scopeType() == RuleScopeType.DEFAULT ? "默认规则" : rule.channelDisplayName();
    }

    private static String scopeLabel(ReconciliationRuleView rule) {
        return rule.scopeType() == RuleScopeType.DEFAULT ? "全局默认" : "渠道专用";
    }

    private static String effectiveVersionLabel(
            ReconciliationRuleView rule, ReconciliationRuleVersionView effective) {
        if (effective == null) {
            return "尚未发布";
        }
        if (rule.activeVersion() == null && rule.scopeType() == RuleScopeType.CHANNEL) {
            return "继承默认规则 · 版本 " + effective.versionNumber();
        }
        return "版本 " + effective.versionNumber();
    }

    private static String amount(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private static String pendingSummary(ReconciliationRuleVersionView draft) {
        return amount(draft.amountToleranceCents()) + " 元 / " + draft.queryWindowHours() + " 小时";
    }

    public record RuleRow(
            UUID id,
            String title,
            String scopeLabel,
            String activeVersionLabel,
            String amountLabel,
            String windowLabel,
            boolean hasDraft,
            String draftStatusLabel,
            String pendingSummary,
            UUID draftId,
            Long draftAmountToleranceCents,
            Integer draftQueryWindowHours,
            String channelCode,
            Boolean channelActive,
            String channelStatusLabel) {}

    public record VersionRow(
            int versionNumber,
            String statusLabel,
            String amountLabel,
            String windowLabel,
            String createdBy,
            java.time.Instant createdAt,
            String publishedBy,
            java.time.Instant publishedAt) {}

    private record DraftInput(
            Long amountToleranceCents,
            Integer queryWindowHours,
            String amountError,
            String windowError) {
        boolean valid() {
            return amountError == null && windowError == null;
        }
    }

    private record DraftSnapshot(
            boolean present,
            UUID id,
            Long amountToleranceCents,
            Integer queryWindowHours) {
        private static DraftSnapshot from(ReconciliationRuleVersionView draft) {
            return draft == null
                    ? new DraftSnapshot(false, null, null, null)
                    : new DraftSnapshot(
                            true,
                            draft.id(),
                            draft.amountToleranceCents(),
                            draft.queryWindowHours());
        }
    }
}
