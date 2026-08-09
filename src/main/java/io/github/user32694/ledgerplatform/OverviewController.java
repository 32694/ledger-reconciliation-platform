package io.github.user32694.ledgerplatform;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnBean({AccountsApi.class, PaymentsApi.class, ReconciliationApi.class})
public class OverviewController {
    private final AccountsApi accountsApi;
    private final PaymentsApi paymentsApi;
    private final ReconciliationApi reconciliationApi;

    public OverviewController(
            AccountsApi accountsApi,
            PaymentsApi paymentsApi,
            ReconciliationApi reconciliationApi) {
        this.accountsApi = accountsApi;
        this.paymentsApi = paymentsApi;
        this.reconciliationApi = reconciliationApi;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/admin")
    String overview(Model model) {
        var accounts = accountsApi.findAll();
        BigDecimal customerBalance = accounts.stream()
                .map(account -> BigDecimal.valueOf(
                        accountsApi.balance(account.id()).cents(), 2))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        model.addAttribute("accountCount", accounts.size());
        model.addAttribute("customerBalance", customerBalance);
        model.addAttribute("recentPayments", paymentsApi.findRecent(8).stream()
                .map(payment -> new PaymentRow(
                        payment.id(),
                        payment.channelReference(),
                        payment.type(),
                        paymentTypeLabel(payment.type()),
                        BigDecimal.valueOf(payment.amountCents(), 2),
                        payment.status(),
                        paymentStatusLabel(payment.status())))
                .toList());
        model.addAttribute("reconciliationOperations", reconciliationApi.getOperationsSummary());
        model.addAttribute("activeNav", "overview");
        return "admin/overview";
    }

    private static String paymentTypeLabel(String type) {
        return switch (type) {
            case "TOP_UP" -> "充值";
            case "TRANSFER" -> "转账";
            case "REFUND" -> "充值退款";
            case "REVERSAL" -> "转账冲正";
            default -> type;
        };
    }

    private static String paymentStatusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "处理中";
            case "SUCCEEDED" -> "成功";
            case "FAILED" -> "失败";
            default -> status;
        };
    }

    public record PaymentRow(
            java.util.UUID id,
            String channelReference,
            String type,
            String typeLabel,
            BigDecimal amount,
            String status,
            String statusLabel) {}
}
