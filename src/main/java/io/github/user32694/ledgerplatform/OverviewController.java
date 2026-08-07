package io.github.user32694.ledgerplatform;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
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
        long customerBalanceCents = accounts.stream()
                .mapToLong(account -> accountsApi.balance(account.id()).cents())
                .sum();
        model.addAttribute("accountCount", accounts.size());
        model.addAttribute("customerBalanceCents", customerBalanceCents);
        model.addAttribute("recentPayments", paymentsApi.findRecent(8));
        model.addAttribute("activeNav", "overview");
        return "admin/overview";
    }
}
