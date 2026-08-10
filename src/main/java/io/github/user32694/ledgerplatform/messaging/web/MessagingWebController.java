package io.github.user32694.ledgerplatform.messaging.web;

import io.github.user32694.ledgerplatform.messaging.MessagingOperationsApi;
import io.github.user32694.ledgerplatform.messaging.OutboxEventView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class MessagingWebController {
    private final MessagingOperationsApi messagingApi;

    public MessagingWebController(MessagingOperationsApi messagingApi) {
        this.messagingApi = messagingApi;
    }

    @GetMapping("/admin/messaging")
    String messaging(Model model) {
        model.addAttribute("summary", messagingApi.summary());
        model.addAttribute("queues", messagingApi.queueDepths());
        model.addAttribute("events", messagingApi.findRecent(100).stream()
                .map(MessagingWebController::toRow)
                .toList());
        model.addAttribute("activeNav", "messaging");
        return "admin/messaging";
    }

    @PostMapping("/admin/messaging/{id}/retry")
    String retry(@PathVariable UUID id, RedirectAttributes redirect) {
        messagingApi.retryFailed(id);
        redirect.addFlashAttribute("message", "事件已重新加入投递队列");
        return "redirect:/admin/messaging";
    }

    private static OutboxEventRow toRow(OutboxEventView event) {
        return new OutboxEventRow(
                event.id(),
                event.aggregateId(),
                eventTypeLabel(event.eventType()),
                aggregateTypeLabel(event.aggregateType()),
                event.status(),
                statusLabel(event.status()),
                statusClass(event.status()),
                event.attemptCount(),
                event.lastError(),
                event.createdAt());
    }

    private static String eventTypeLabel(String eventType) {
        return switch (eventType) {
            case "PAYMENT_SUCCEEDED" -> "支付成功";
            case "RECONCILIATION_COMPLETED" -> "对账完成";
            default -> eventType;
        };
    }

    private static String aggregateTypeLabel(String aggregateType) {
        return switch (aggregateType) {
            case "PAYMENT" -> "资金操作";
            case "RECONCILIATION_BATCH" -> "对账批次";
            default -> aggregateType;
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "待投递";
            case "PUBLISHING" -> "投递中";
            case "PUBLISHED" -> "已投递";
            case "FAILED" -> "投递失败";
            default -> status;
        };
    }

    private static String statusClass(String status) {
        return switch (status) {
            case "PUBLISHED" -> "status-success";
            case "FAILED" -> "status-danger";
            default -> "";
        };
    }

    public record OutboxEventRow(
            UUID id,
            String aggregateId,
            String eventTypeLabel,
            String aggregateTypeLabel,
            String status,
            String statusLabel,
            String statusClass,
            int attemptCount,
            String lastError,
            Instant createdAt) {}
}
