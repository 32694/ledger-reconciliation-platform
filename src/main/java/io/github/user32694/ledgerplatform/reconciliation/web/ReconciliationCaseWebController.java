package io.github.user32694.ledgerplatform.reconciliation.web;

import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseEventView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionCode;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/reconciliation/cases")
public class ReconciliationCaseWebController {
    private static final String CASE_ERROR = "操作失败，案件状态或负责人可能已变更";

    private final ReconciliationApi reconciliationApi;

    public ReconciliationCaseWebController(ReconciliationApi reconciliationApi) {
        this.reconciliationApi = reconciliationApi;
    }

    @GetMapping
    String list(
            @RequestParam(required = false) ResultType resultType,
            @RequestParam(required = false) ResolutionStatus resolutionStatus,
            @RequestParam(required = false) String assignee,
            @RequestParam(defaultValue = "false") boolean onlyMine,
            Authentication authentication,
            Model model) {
        String normalizedAssignee = normalizeAssignee(assignee);
        String effectiveAssignee = onlyMine ? authentication.getName() : normalizedAssignee;
        List<ReconciliationCaseView> cases = reconciliationApi.findCases(
                resultType, resolutionStatus, effectiveAssignee);
        if (resolutionStatus == null) {
            cases = cases.stream()
                    .filter(caseView -> caseView.resolutionStatus() == ResolutionStatus.OPEN
                            || caseView.resolutionStatus() == ResolutionStatus.CLAIMED)
                    .toList();
        }

        model.addAttribute("cases", cases.stream().map(ReconciliationCaseWebController::toCaseRow).toList());
        model.addAttribute("resultTypes", List.of(
                resultTypeOption(ResultType.AMOUNT_MISMATCH),
                resultTypeOption(ResultType.CHANNEL_ONLY),
                resultTypeOption(ResultType.INTERNAL_ONLY)));
        model.addAttribute("resolutionStatuses", List.of(
                resolutionStatusOption(ResolutionStatus.OPEN),
                resolutionStatusOption(ResolutionStatus.CLAIMED),
                resolutionStatusOption(ResolutionStatus.RESOLVED)));
        model.addAttribute("selectedResultType", resultType == null ? "" : resultType.name());
        model.addAttribute("selectedResolutionStatus", resolutionStatus == null ? "" : resolutionStatus.name());
        model.addAttribute("selectedAssignee", normalizedAssignee == null ? "" : normalizedAssignee);
        model.addAttribute("onlyMine", onlyMine);
        model.addAttribute("activeNav", "reconciliation-cases");
        return "admin/reconciliation-cases";
    }

    @GetMapping("/{caseId}")
    String detail(@PathVariable UUID caseId, Authentication authentication, Model model) {
        // 案件页集中展示渠道、支付、账本和审计证据；处理动作必须绑定当前登录人。
        var details = reconciliationApi.getResult(caseId);
        var caseView = details.caseView();
        boolean isOwner = caseView.resolutionStatus() == ResolutionStatus.CLAIMED
                && authentication.getName().equals(caseView.assignedTo());

        model.addAttribute("caseView", caseView);
        model.addAttribute("resultTypeLabel", resultTypeLabel(caseView.resultType()));
        model.addAttribute("resolutionStatusLabel", resolutionStatusLabel(caseView.resolutionStatus()));
        model.addAttribute("resolutionCodeLabel", caseView.resolutionCode() == null
                ? null : resolutionCodeLabel(caseView.resolutionCode()));
        model.addAttribute("timeline", details.timeline().stream()
                .map(ReconciliationCaseWebController::toTimelineRow)
                .toList());
        model.addAttribute("resolutionCodes", List.of(
                resolutionCodeOption(ResolutionCode.INTERNAL_CONFIRMED),
                resolutionCodeOption(ResolutionCode.CHANNEL_CONFIRMED),
                resolutionCodeOption(ResolutionCode.IGNORED_TEST_DATA),
                resolutionCodeOption(ResolutionCode.OTHER)));
        model.addAttribute("canClaim", caseView.resolutionStatus() == ResolutionStatus.OPEN);
        model.addAttribute("canRelease", isOwner);
        model.addAttribute("canResolve", isOwner);
        model.addAttribute("activeNav", "reconciliation-cases");
        return "admin/reconciliation-case-detail";
    }

    @PostMapping("/{caseId}/claim")
    String claim(
            @PathVariable UUID caseId,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            reconciliationApi.claim(caseId, authentication.getName());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("caseError", CASE_ERROR);
        }
        return redirect(caseId);
    }

    @PostMapping("/{caseId}/release")
    String release(
            @PathVariable UUID caseId,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            reconciliationApi.release(caseId, authentication.getName());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("caseError", CASE_ERROR);
        }
        return redirect(caseId);
    }

    @PostMapping("/{caseId}/resolve")
    String resolve(
            @PathVariable UUID caseId,
            @RequestParam ResolutionCode resolutionCode,
            @RequestParam String note,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        // 解决动作只记录人工决定和备注，不自动修改原始支付或账本证据。
        try {
            reconciliationApi.resolve(caseId, resolutionCode, note, authentication.getName());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("caseError", CASE_ERROR);
        }
        return redirect(caseId);
    }

    private static String redirect(UUID caseId) {
        return "redirect:/admin/reconciliation/cases/" + caseId;
    }

    private static String normalizeAssignee(String assignee) {
        if (assignee == null || assignee.isBlank()) {
            return null;
        }
        return assignee.strip();
    }

    private static CaseRow toCaseRow(ReconciliationCaseView caseView) {
        return new CaseRow(caseView, resultTypeLabel(caseView.resultType()),
                resolutionStatusLabel(caseView.resolutionStatus()));
    }

    private static TimelineRow toTimelineRow(ReconciliationCaseEventView event) {
        return new TimelineRow(
                event, caseActionLabel(event.action()),
                event.resolutionCode() == null ? null : resolutionCodeLabel(event.resolutionCode()));
    }

    private static EnumOption resultTypeOption(ResultType type) {
        return new EnumOption(type.name(), resultTypeLabel(type));
    }

    private static EnumOption resolutionStatusOption(ResolutionStatus status) {
        return new EnumOption(status.name(), resolutionStatusLabel(status));
    }

    private static EnumOption resolutionCodeOption(ResolutionCode code) {
        return new EnumOption(code.name(), resolutionCodeLabel(code));
    }

    private static String resultTypeLabel(ResultType type) {
        return switch (type) {
            case MATCHED -> "匹配一致";
            case AMOUNT_MISMATCH -> "金额不一致";
            case CHANNEL_ONLY -> "仅渠道存在";
            case INTERNAL_ONLY -> "仅内部存在";
        };
    }

    private static String resolutionStatusLabel(ResolutionStatus status) {
        return switch (status) {
            case NOT_REQUIRED -> "无需处理";
            case OPEN -> "待处理";
            case CLAIMED -> "处理中";
            case RESOLVED -> "已解决";
        };
    }

    private static String resolutionCodeLabel(ResolutionCode code) {
        return switch (code) {
            case INTERNAL_CONFIRMED -> "内部账务为准";
            case CHANNEL_CONFIRMED -> "渠道账单为准";
            case IGNORED_TEST_DATA -> "忽略测试数据";
            case OTHER -> "其他";
        };
    }

    private static String caseActionLabel(String action) {
        return switch (action) {
            case "CLAIMED" -> "已认领";
            case "RELEASED" -> "已取消认领";
            case "RESOLVED" -> "已解决";
            default -> action;
        };
    }

    public record CaseRow(ReconciliationCaseView caseView, String resultTypeLabel, String statusLabel) {}

    public record TimelineRow(
            ReconciliationCaseEventView event, String actionLabel, String resolutionCodeLabel) {}

    public record EnumOption(String value, String label) {}
}
