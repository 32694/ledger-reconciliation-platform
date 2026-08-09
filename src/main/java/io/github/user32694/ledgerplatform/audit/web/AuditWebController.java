package io.github.user32694.ledgerplatform.audit.web;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuditWebController {
    private static final Pattern PAYMENT_SUMMARY_PATTERN = Pattern.compile(
            "^(TOP_UP|TRANSFER|REFUND|REVERSAL) CNY (\\d+) (SUCCEEDED|FAILED)(?: ([A-Z][A-Z0-9_]*))?$");

    private final AuditApi auditApi;

    public AuditWebController(AuditApi auditApi) {
        this.auditApi = auditApi;
    }

    @GetMapping("/admin/audit")
    String list(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditOutcome outcome,
            Model model) {
        model.addAttribute("events", auditApi.findRecent(action, outcome, 100).stream()
                .map(event -> new AuditEventRow(
                        event.actor(),
                        actionLabel(event.action()),
                        aggregateTypeLabel(event.aggregateType()),
                        event.aggregateId(),
                        event.outcome(),
                        outcomeLabel(event.outcome()),
                        summaryLabel(event.action(), event.outcome(), event.summary()),
                        textOrPlaceholder(event.correlationReference(), "—"),
                        event.occurredAt()))
                .toList());
        model.addAttribute("selectedAction", action);
        model.addAttribute("selectedOutcome", outcome);
        model.addAttribute("actions", Arrays.stream(AuditAction.values())
                .map(value -> new FilterOption(value.name(), actionLabel(value)))
                .toList());
        model.addAttribute("outcomes", Arrays.stream(AuditOutcome.values())
                .map(value -> new FilterOption(value.name(), outcomeLabel(value)))
                .toList());
        model.addAttribute("activeNav", "audit");
        return "admin/audit-list";
    }

    private static String actionLabel(AuditAction action) {
        return switch (action) {
            case ACCOUNT_CREATE -> "账户创建";
            case PAYMENT_TOP_UP -> "账户充值";
            case PAYMENT_TRANSFER -> "账户转账";
            case PAYMENT_REFUND -> "充值退款";
            case PAYMENT_REVERSAL -> "转账冲正";
            case RECONCILIATION_IMPORT -> "对账导入";
            case RECONCILIATION_RUN -> "执行对账";
            case RECONCILIATION_CASE_CLAIM -> "认领差异";
            case RECONCILIATION_CASE_RELEASE -> "取消认领";
            case RECONCILIATION_CASE_RESOLVE -> "解决差异";
            case RECONCILIATION_RESOLVE -> "处理差异（旧版）";
            case RECONCILIATION_RULE_DRAFT_SAVE -> "保存对账规则草稿";
            case RECONCILIATION_RULE_PUBLISH -> "发布对账规则";
            case RECONCILIATION_CHANNEL_STATUS_CHANGE -> "变更对账渠道状态";
        };
    }

    private static String outcomeLabel(AuditOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "成功";
            case FAILED -> "失败";
        };
    }

    private static String aggregateTypeLabel(String aggregateType) {
        return switch (aggregateType) {
            case "ACCOUNT" -> "客户账户";
            case "PAYMENT" -> "资金操作";
            case "RECONCILIATION_BATCH" -> "对账批次";
            case "RECONCILIATION_RESULT", "RECONCILIATION_DIFFERENCE" -> "对账差异";
            case "RECONCILIATION_RULE" -> "对账规则";
            case "RECONCILIATION_CHANNEL" -> "对账渠道";
            default -> "业务对象";
        };
    }

    private static String summaryLabel(
            AuditAction action, AuditOutcome outcome, String summary) {
        String displaySummary = textOrPlaceholder(summary, "暂无摘要");
        String expectedPaymentType = paymentType(action);
        if (expectedPaymentType == null) {
            return displaySummary;
        }

        var matcher = PAYMENT_SUMMARY_PATTERN.matcher(displaySummary);
        if (!matcher.matches()
                || !expectedPaymentType.equals(matcher.group(1))
                || !outcome.name().equals(matcher.group(3))) {
            return displaySummary;
        }
        if (matcher.group(4) != null && outcome != AuditOutcome.FAILED) {
            return displaySummary;
        }

        String amount = new BigDecimal(matcher.group(2))
                .movePointLeft(2)
                .setScale(2)
                .toPlainString();
        String localized = actionLabel(action) + outcomeLabel(outcome) + "，人民币 " + amount;
        if (matcher.group(4) != null) {
            localized += "，" + failureReasonLabel(matcher.group(4));
        }
        return localized;
    }

    private static String paymentType(AuditAction action) {
        return switch (action) {
            case PAYMENT_TOP_UP -> "TOP_UP";
            case PAYMENT_TRANSFER -> "TRANSFER";
            case PAYMENT_REFUND -> "REFUND";
            case PAYMENT_REVERSAL -> "REVERSAL";
            default -> null;
        };
    }

    private static String failureReasonLabel(String failureReason) {
        return switch (failureReason) {
            case "INSUFFICIENT_FUNDS" -> "余额不足";
            case "BALANCE_LIMIT_EXCEEDED" -> "账户余额超出系统支持范围";
            default -> "原因请查看关联业务";
        };
    }

    private static String textOrPlaceholder(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
    }

    public record FilterOption(String value, String label) {}

    public record AuditEventRow(
            String actor,
            String actionLabel,
            String aggregateTypeLabel,
            String aggregateId,
            AuditOutcome outcome,
            String outcomeLabel,
            String summary,
            String correlationReference,
            Instant occurredAt) {}
}
