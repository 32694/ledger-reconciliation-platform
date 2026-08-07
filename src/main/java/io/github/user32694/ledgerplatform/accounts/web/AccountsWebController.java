package io.github.user32694.ledgerplatform.accounts.web;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/accounts")
public class AccountsWebController {
    private final AccountsApi accountsApi;

    public AccountsWebController(AccountsApi accountsApi) {
        this.accountsApi = accountsApi;
    }

    @GetMapping
    String accounts(
            @RequestHeader(name = "HX-Request", required = false) boolean htmx, Model model) {
        addAccounts(model);
        if (htmx) {
            return "admin/accounts :: accountTable";
        }
        model.addAttribute("activeNav", "accounts");
        return "admin/accounts";
    }

    @GetMapping("/new")
    String accountForm(Model model) {
        model.addAttribute("accountForm", new AccountForm());
        model.addAttribute("activeNav", "accounts");
        return "admin/account-form";
    }

    @PostMapping
    String create(
            @Valid @ModelAttribute("accountForm") AccountForm form,
            BindingResult bindingResult,
            Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("activeNav", "accounts");
            return "admin/account-form";
        }
        try {
            accountsApi.create(form.getOwnerName());
        } catch (IllegalArgumentException exception) {
            bindingResult.rejectValue("ownerName", "account.ownerName", exception.getMessage());
            model.addAttribute("activeNav", "accounts");
            return "admin/account-form";
        }
        return "redirect:/admin/accounts";
    }

    private void addAccounts(Model model) {
        List<AccountRow> accountRows = accountsApi.findAll().stream()
                .map(account -> new AccountRow(
                        account.id(),
                        account.accountNumber(),
                        account.ownerName(),
                        account.status(),
                        BigDecimal.valueOf(accountsApi.balance(account.id()).cents(), 2)))
                .toList();
        model.addAttribute("accounts", accountRows);
    }

    public record AccountRow(
            UUID id, String accountNumber, String ownerName, String status, BigDecimal balance) {}

    public static class AccountForm {
        @NotBlank(message = "Owner name is required")
        private String ownerName;

        public String getOwnerName() {
            return ownerName;
        }

        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
        }
    }
}
