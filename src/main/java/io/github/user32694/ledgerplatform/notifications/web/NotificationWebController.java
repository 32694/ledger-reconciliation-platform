package io.github.user32694.ledgerplatform.notifications.web;

import io.github.user32694.ledgerplatform.notifications.NotificationView;
import io.github.user32694.ledgerplatform.notifications.NotificationsApi;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class NotificationWebController {
    private final NotificationsApi notificationsApi;

    public NotificationWebController(NotificationsApi notificationsApi) {
        this.notificationsApi = notificationsApi;
    }

    @GetMapping("/admin/notifications")
    String notifications(Model model) {
        model.addAttribute("notifications", notificationsApi.findRecent(100).stream()
                .map(NotificationWebController::toRow)
                .toList());
        model.addAttribute("activeNav", "notifications");
        return "admin/notifications";
    }

    @PostMapping("/admin/notifications/{id}/read")
    String markRead(@PathVariable UUID id) {
        notificationsApi.markRead(id);
        return "redirect:/admin/notifications";
    }

    private static NotificationRow toRow(NotificationView notification) {
        return new NotificationRow(
                notification.id(),
                notification.title(),
                notification.content(),
                typeLabel(notification.notificationType()),
                aggregateTypeLabel(notification.aggregateType()),
                notification.aggregateId(),
                notification.createdAt(),
                notification.readAt(),
                notification.readAt() == null);
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "PAYMENT_SUCCEEDED" -> "支付成功";
            case "RECONCILIATION_COMPLETED" -> "对账完成";
            default -> type;
        };
    }

    private static String aggregateTypeLabel(String type) {
        return switch (type) {
            case "PAYMENT" -> "资金操作";
            case "RECONCILIATION_BATCH" -> "对账批次";
            default -> type;
        };
    }

    public record NotificationRow(
            UUID id,
            String title,
            String content,
            String typeLabel,
            String aggregateTypeLabel,
            String aggregateId,
            Instant createdAt,
            Instant readAt,
            boolean unread) {}
}
