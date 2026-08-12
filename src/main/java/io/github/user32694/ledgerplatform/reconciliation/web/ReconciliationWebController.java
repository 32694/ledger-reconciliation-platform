package io.github.user32694.ledgerplatform.reconciliation.web;

import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationCaseProgress;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRulesApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationResultView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationRunView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import io.github.user32694.ledgerplatform.reconciliation.RunStatus;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/admin/reconciliation")
public class ReconciliationWebController {
    private final ReconciliationApi reconciliationApi;
    private final ReconciliationRulesApi reconciliationRulesApi;

    public ReconciliationWebController(
            ReconciliationApi reconciliationApi,
            ReconciliationRulesApi reconciliationRulesApi) {
        this.reconciliationApi = reconciliationApi;
        this.reconciliationRulesApi = reconciliationRulesApi;
    }

    @GetMapping
    String list(@RequestParam(required = false) RunStatus runStatus, Model model) {
        // 列表页同时展示批次和最近一次运行；HTMX 后续只刷新运行状态片段。
        var batches = new ArrayList<BatchRow>();
        Map<UUID, ReconciliationCaseProgress> progressByBatch = reconciliationApi
                .findCaseProgresses().stream()
                .collect(Collectors.toMap(ReconciliationCaseProgress::batchId, progress -> progress));
        for (var batch : reconciliationApi.findBatches()) {
            var selectedRun = reconciliationApi.findRuns(batch.id()).stream()
                    .filter(run -> runStatus == null || run.status() == runStatus)
                    .findFirst()
                    .orElse(null);
            if (runStatus == null || selectedRun != null) {
                batches.add(toBatchRow(
                        batch,
                        selectedRun == null ? null : toRunRow(selectedRun),
                        progressByBatch.get(batch.id())));
            }
        }
        model.addAttribute("batches", batches);
        model.addAttribute("selectedRunStatus", runStatus == null ? null : runStatus.name());
        model.addAttribute("listTitle", runStatus == null ? "对账批次" : runStatusFilterLabel(runStatus));
        model.addAttribute("activeNav", "reconciliation");
        return "admin/reconciliation-list";
    }

    @GetMapping("/import")
    String importForm(Model model) {
        populateImportForm(model, null);
        return "admin/reconciliation-import";
    }

    @GetMapping("/import/rule-preview")
    String rulePreview(@RequestParam String channelCode, Model model) {
        // 选择渠道时预览当前已发布规则，帮助操作员在上传前确认容差和查询窗口。
        try {
            var rule = reconciliationRulesApi.resolvePublishedVersion(channelCode);
            model.addAttribute("rule", rule);
            model.addAttribute(
                    "ruleScopeLabel",
                    rule.sourceScope() == io.github.user32694.ledgerplatform.reconciliation.RuleScopeType.CHANNEL
                            ? "渠道规则" : "默认规则");
        } catch (RuntimeException exception) {
            model.addAttribute("previewError", "无法解析该渠道的已发布规则");
        }
        return "admin/fragments/reconciliation-rule-preview";
    }

    @PostMapping("/import")
    String importStatement(
            @RequestParam("file") MultipartFile file,
            @RequestParam("channelCode") String channelCode,
            Authentication authentication,
            Model model) {
        // 控制器只负责表单校验和错误回显，实际导入由领域 API 处理。
        if (file == null || file.isEmpty()) {
            model.addAttribute("importError", "请选择要导入的 CSV 文件");
            populateImportForm(model, channelCode);
            return "admin/reconciliation-import";
        }
        try {
            var batch = reconciliationApi.importStatement(new StatementUpload(
                    channelCode,
                    file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename(),
                    file.getBytes(),
                    authentication.getName()));
            return "redirect:/admin/reconciliation/" + batch.id();
        } catch (Exception exception) {
            model.addAttribute("importError", "文件导入失败，请检查格式后重试");
            populateImportForm(model, channelCode);
            return "admin/reconciliation-import";
        }
    }

    @GetMapping("/{batchId}")
    String detail(
            @PathVariable UUID batchId,
            @RequestParam(required = false) ResultType resultType,
            @RequestParam(required = false) ResolutionStatus resolutionStatus,
            Model model) {
        // 详情页把批次、运行历史和差异结果放在同一视图，筛选条件通过查询参数传入。
        ReconciliationBatchView batch = reconciliationApi.getBatch(batchId);
        List<RunRow> runs = reconciliationApi.findRuns(batchId).stream()
                .map(ReconciliationWebController::toRunRow)
                .toList();
        RunRow latestRun = runs.isEmpty() ? null : runs.get(0);
        List<ResultRow> results = reconciliationApi.findResults(batchId, resultType, resolutionStatus).stream()
                .map(ReconciliationWebController::toResultRow)
                .toList();
        model.addAttribute("batch", toBatchRow(batch, latestRun));
        model.addAttribute("batchId", batchId);
        model.addAttribute("runs", runs);
        model.addAttribute("run", latestRun);
        model.addAttribute("latestRun", latestRun);
        model.addAttribute("results", results);
        model.addAttribute("resultTypes", Arrays.stream(ResultType.values())
                .map(type -> new FilterOption(type.name(), resultTypeLabel(type)))
                .toList());
        model.addAttribute("resolutionStatuses", Arrays.stream(ResolutionStatus.values())
                .map(status -> new FilterOption(status.name(), resolutionFilterLabel(status)))
                .toList());
        model.addAttribute("selectedResultType", resultType == null ? "" : resultType.name());
        model.addAttribute("selectedResolutionStatus", resolutionStatus == null ? "" : resolutionStatus.name());
        model.addAttribute("canRun", (batch.status() == BatchStatus.IMPORTED
                || batch.status() == BatchStatus.RECONCILIATION_FAILED)
                && (latestRun == null || !latestRun.active()));
        model.addAttribute("activeNav", "reconciliation");
        return "admin/reconciliation-detail";
    }

    @PostMapping("/{batchId}/run")
    String run(
            @PathVariable UUID batchId,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        // 启动作业是异步操作，提交后回到详情页由前端轮询状态。
        try {
            reconciliationApi.startRun(batchId, authentication.getName());
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("runError", "无法启动对账，请稍后重试");
        }
        return "redirect:/admin/reconciliation/" + batchId;
    }

    @PostMapping("/runs/{runId}/restart")
    String restart(
            @PathVariable UUID runId,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        // 重启沿用 Batch checkpoint，用户无需重新上传文件或从头计算。
        try {
            var run = reconciliationApi.restartRun(runId, authentication.getName());
            return "redirect:/admin/reconciliation/" + run.batchId();
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute("runError", "无法从断点继续，请稍后重试");
            return "redirect:/admin/reconciliation";
        }
    }

    @GetMapping("/{batchId}/run-status")
    String runStatus(
            @PathVariable UUID batchId,
            Model model,
            HttpServletResponse response) {
        var latestRun = reconciliationApi.findRuns(batchId).stream()
                .findFirst()
                .map(ReconciliationWebController::toRunRow)
                .orElse(null);
        model.addAttribute("batchId", batchId);
        model.addAttribute("run", latestRun);
        if (latestRun != null && !latestRun.active()) {
            response.setHeader("HX-Refresh", "true");
        }
        return "admin/fragments/reconciliation-run-status :: status";
    }

    private static BatchRow toBatchRow(ReconciliationBatchView batch, RunRow latestRun) {
        return toBatchRow(batch, latestRun, null);
    }

    private static BatchRow toBatchRow(
            ReconciliationBatchView batch,
            RunRow latestRun,
            ReconciliationCaseProgress progress) {
        return new BatchRow(
                batch.id(), batch.fileName(), batch.status(), statusLabel(batch.status()),
                batch.channelDisplayName(), batch.ruleVersionNumber(),
                formatCents(batch.amountToleranceCents()), batch.queryWindowHours(),
                batch.totalRows(), batch.matchedRows(), batch.differenceRows(), batch.errorMessage(),
                caseProgressLabel(batch, progress), latestRun);
    }

    private static ResultRow toResultRow(ReconciliationResultView result) {
        return new ResultRow(
                result.id(), result.batchId(), result.statementEntryId(), result.paymentId(),
                result.channelTransactionId(), result.channelAmountCents(), result.internalAmountCents(),
                result.resultType(), resultTypeLabel(result.resultType()), result.resolutionStatus(),
                resolutionLabel(result.resolutionStatus()), result.resolutionNote(), result.resolvedBy());
    }

    private static RunRow toRunRow(ReconciliationRunView run) {
        return new RunRow(
                run.id(), run.attemptNumber(), run.status(), runStatusLabel(run.status()),
                run.requestedBy(), run.requestedAt(), run.startedAt(), run.completedAt(),
                durationLabel(run.startedAt(), run.completedAt()),
                run.matchedRows(), run.differenceRows(), run.errorMessage(),
                run.status() == RunStatus.QUEUED || run.status() == RunStatus.RUNNING,
                run.batchJobInstanceId(), run.batchJobExecutionId(), currentStepLabel(run.currentStep()),
                run.processedItems(), run.totalItems(), progressPercent(run.processedItems(), run.totalItems()),
                run.restartCount());
    }

    private static String formatCents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString() + " 元";
    }

    private static String currentStepLabel(String step) {
        return switch (step == null ? "prepareReconciliationStep" : step) {
            case "prepareReconciliationStep" -> "准备任务";
            case "matchStatementEntriesStep" -> "匹配渠道账单";
            case "findInternalOnlyPaymentsStep" -> "扫描内部单边";
            case "finalizeReconciliationStep" -> "汇总结果";
            default -> "准备任务";
        };
    }

    private static int progressPercent(int processedItems, int totalItems) {
        if (totalItems <= 0) {
            return 0;
        }
        long percentage = Math.max(0, (long) processedItems) * 100 / totalItems;
        return (int) Math.min(100, percentage);
    }

    private static String durationLabel(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null) {
            return "--";
        }
        return Duration.between(startedAt, completedAt).toSeconds() + " 秒";
    }

    private static String statusLabel(BatchStatus status) {
        return switch (status) {
            case IMPORTED -> "待对账";
            case RUNNING -> "对账中";
            case COMPLETED -> "已完成";
            case IMPORT_FAILED -> "导入失败";
            case RECONCILIATION_FAILED -> "对账失败";
        };
    }

    private static String resultTypeLabel(ResultType type) {
        return switch (type) {
            case MATCHED -> "匹配一致";
            case AMOUNT_MISMATCH -> "金额不一致";
            case CHANNEL_ONLY -> "仅渠道存在";
            case INTERNAL_ONLY -> "仅内部存在";
        };
    }

    private static String runStatusLabel(RunStatus status) {
        return switch (status) {
            case QUEUED -> "等待执行";
            case RUNNING -> "对账中";
            case SUCCEEDED -> "已完成";
            case FAILED -> "执行失败";
        };
    }

    private static String runStatusFilterLabel(RunStatus status) {
        return switch (status) {
            case QUEUED -> "等待执行任务";
            case RUNNING -> "执行中任务";
            case SUCCEEDED -> "成功任务";
            case FAILED -> "失败任务";
        };
    }

    private static String resolutionLabel(ResolutionStatus status) {
        return switch (status) {
            case NOT_REQUIRED -> "无需处理";
            case OPEN -> "待处理";
            case CLAIMED -> "处理中";
            case RESOLVED -> "已处理";
        };
    }

    private static String resolutionFilterLabel(ResolutionStatus status) {
        return status == ResolutionStatus.RESOLVED ? "已解决" : resolutionLabel(status);
    }

    private static String caseProgressLabel(
            ReconciliationBatchView batch, ReconciliationCaseProgress progress) {
        if (progress == null) {
            return batch.status() == BatchStatus.COMPLETED ? "无需处理" : "尚无异常";
        }
        return "已解决 " + progress.resolvedCount() + " / " + progress.totalCount();
    }

    public record BatchRow(
            UUID id,
            String fileName,
            BatchStatus status,
            String statusLabel,
            String channelDisplayName,
            int ruleVersionNumber,
            String amountToleranceLabel,
            int queryWindowHours,
            int totalRows,
            int matchedRows,
            int differenceRows,
            String errorMessage,
            String caseProgressLabel,
            RunRow latestRun) {}

    public record FilterOption(String value, String label) {}

    public record ResultRow(
            UUID id,
            UUID batchId,
            UUID statementEntryId,
            UUID paymentId,
            String channelTransactionId,
            Long channelAmountCents,
            Long internalAmountCents,
            ResultType resultType,
            String resultTypeLabel,
            ResolutionStatus resolutionStatus,
            String resolutionLabel,
            String resolutionNote,
            String resolvedBy) {}

    public record RunRow(
            UUID id,
            int attemptNumber,
            RunStatus status,
            String statusLabel,
            String requestedBy,
            Instant requestedAt,
            Instant startedAt,
            Instant completedAt,
            String durationLabel,
            int matchedRows,
            int differenceRows,
            String errorMessage,
            boolean active,
            Long batchJobInstanceId,
            Long batchJobExecutionId,
            String currentStepLabel,
            int processedItems,
            int totalItems,
            int progressPercent,
            int restartCount) {}

    private void populateImportForm(Model model, String selectedChannelCode) {
        model.addAttribute("channels", reconciliationRulesApi.findChannels(false));
        model.addAttribute("selectedChannelCode", selectedChannelCode);
        model.addAttribute("activeNav", "reconciliation");
    }
}
