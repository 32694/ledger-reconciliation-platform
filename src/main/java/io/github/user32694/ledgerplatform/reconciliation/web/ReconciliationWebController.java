package io.github.user32694.ledgerplatform.reconciliation.web;

import io.github.user32694.ledgerplatform.reconciliation.BatchStatus;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationBatchView;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationResultView;
import io.github.user32694.ledgerplatform.reconciliation.ResolutionStatus;
import io.github.user32694.ledgerplatform.reconciliation.ResultType;
import io.github.user32694.ledgerplatform.reconciliation.StatementUpload;
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
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/admin/reconciliation")
public class ReconciliationWebController {
    private final ReconciliationApi reconciliationApi;

    public ReconciliationWebController(ReconciliationApi reconciliationApi) {
        this.reconciliationApi = reconciliationApi;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("batches", reconciliationApi.findBatches().stream()
                .map(ReconciliationWebController::toBatchRow)
                .toList());
        model.addAttribute("activeNav", "reconciliation");
        return "admin/reconciliation-list";
    }

    @GetMapping("/import")
    String importForm(Model model) {
        model.addAttribute("activeNav", "reconciliation");
        return "admin/reconciliation-import";
    }

    @PostMapping("/import")
    String importStatement(
            @RequestParam("file") MultipartFile file,
            Authentication authentication,
            Model model) {
        if (file == null || file.isEmpty()) {
            model.addAttribute("importError", "请选择要导入的 CSV 文件");
            model.addAttribute("activeNav", "reconciliation");
            return "admin/reconciliation-import";
        }
        try {
            var batch = reconciliationApi.importStatement(new StatementUpload(
                    file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename(),
                    file.getBytes(),
                    authentication.getName()));
            return "redirect:/admin/reconciliation/" + batch.id();
        } catch (Exception exception) {
            model.addAttribute("importError", "文件导入失败，请检查格式后重试");
            model.addAttribute("activeNav", "reconciliation");
            return "admin/reconciliation-import";
        }
    }

    @GetMapping("/{batchId}")
    String detail(
            @PathVariable UUID batchId,
            @RequestParam(required = false) ResultType resultType,
            @RequestParam(required = false) ResolutionStatus resolutionStatus,
            Model model) {
        ReconciliationBatchView batch = reconciliationApi.getBatch(batchId);
        List<ResultRow> results = reconciliationApi.findResults(batchId, resultType, resolutionStatus).stream()
                .map(ReconciliationWebController::toResultRow)
                .toList();
        model.addAttribute("batch", toBatchRow(batch));
        model.addAttribute("results", results);
        model.addAttribute("resultTypes", ResultType.values());
        model.addAttribute("resolutionStatuses", ResolutionStatus.values());
        model.addAttribute("selectedResultType", resultType == null ? "" : resultType.name());
        model.addAttribute("selectedResolutionStatus", resolutionStatus == null ? "" : resolutionStatus.name());
        model.addAttribute("canRun", batch.status() == BatchStatus.IMPORTED
                || batch.status() == BatchStatus.RECONCILIATION_FAILED);
        model.addAttribute("activeNav", "reconciliation");
        return "admin/reconciliation-detail";
    }

    @PostMapping("/{batchId}/run")
    String run(@PathVariable UUID batchId) {
        reconciliationApi.run(batchId);
        return "redirect:/admin/reconciliation/" + batchId;
    }

    @PostMapping("/results/{resultId}/resolve")
    String resolve(
            @PathVariable UUID resultId,
            @RequestParam("note") String note,
            Authentication authentication) {
        var resolved = reconciliationApi.resolve(resultId, note, authentication.getName());
        return "redirect:/admin/reconciliation/" + resolved.batchId();
    }

    private static BatchRow toBatchRow(ReconciliationBatchView batch) {
        return new BatchRow(
                batch.id(), batch.fileName(), batch.status(), statusLabel(batch.status()),
                batch.totalRows(), batch.matchedRows(), batch.differenceRows(), batch.errorMessage());
    }

    private static ResultRow toResultRow(ReconciliationResultView result) {
        return new ResultRow(
                result.id(), result.batchId(), result.statementEntryId(), result.paymentId(),
                result.channelTransactionId(), result.channelAmountCents(), result.internalAmountCents(),
                result.resultType(), resultTypeLabel(result.resultType()), result.resolutionStatus(),
                resolutionLabel(result.resolutionStatus()), result.resolutionNote(), result.resolvedBy());
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

    private static String resolutionLabel(ResolutionStatus status) {
        return switch (status) {
            case NOT_REQUIRED -> "无需处理";
            case OPEN -> "待处理";
            case RESOLVED -> "已处理";
        };
    }

    public record BatchRow(
            UUID id,
            String fileName,
            BatchStatus status,
            String statusLabel,
            int totalRows,
            int matchedRows,
            int differenceRows,
            String errorMessage) {}

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
}
