package io.github.user32694.ledgerplatform;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnBean({AccountsApi.class, PaymentsApi.class})
public class OverviewController {
    private final AccountsApi accountsApi;
    private final PaymentsApi paymentsApi;

    public OverviewController(AccountsApi accountsApi, PaymentsApi paymentsApi) {
        this.accountsApi = accountsApi;
        this.paymentsApi = paymentsApi;
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
                        payment.channelReference(),
                        payment.type(),
                        BigDecimal.valueOf(payment.amountCents(), 2),
                        payment.status()))
                .toList());
        model.addAttribute("activeNav", "overview");
        return "admin/overview";
    }

    public record PaymentRow(
            String channelReference, String type, BigDecimal amount, String status) {}
}
