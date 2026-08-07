package io.github.user32694.ledgerplatform.payments.web;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.IdempotencyConflictException;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/payments")
public class PaymentsWebController {
    private final PaymentsApi paymentsApi;
    private final AccountsApi accountsApi;

    public PaymentsWebController(PaymentsApi paymentsApi, AccountsApi accountsApi) {
        this.paymentsApi = paymentsApi;
        this.accountsApi = accountsApi;
    }

    @GetMapping("/top-up")
    String topUpForm(Model model) {
        if (!model.containsAttribute("topUpForm")) {
            model.addAttribute("topUpForm", new TopUpForm());
        }
        addReferenceData(model);
        return "admin/topup-form";
    }

    @GetMapping("/recent")
    String recentPayments(Model model) {
        model.addAttribute("recentPayments", paymentsApi.findRecent(20));
        return "admin/topup-form :: recentPaymentsTable";
    }

    @PostMapping("/top-up")
    String topUp(
            @Valid @ModelAttribute("topUpForm") TopUpForm form,
            BindingResult bindingResult,
            Model model) {
        if (bindingResult.hasErrors()) {
            addReferenceData(model);
            return "admin/topup-form";
        }
        try {
            paymentsApi.topUp(new TopUpCommand(
                    form.getIdempotencyKey(), form.getAccountId(), form.getAmountCents()));
        } catch (IllegalArgumentException | IdempotencyConflictException exception) {
            bindingResult.reject("topUp.failed", exception.getMessage());
            addReferenceData(model);
            return "admin/topup-form";
        }
        return "redirect:/admin/payments/top-up";
    }

    private void addReferenceData(Model model) {
        model.addAttribute("accounts", accountsApi.findAll());
        model.addAttribute("recentPayments", paymentsApi.findRecent(20));
        model.addAttribute("activeNav", "payments");
    }

    public static class TopUpForm {
        @NotNull(message = "Account is required")
        private UUID accountId;

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        private Long amountCents;

        @NotBlank(message = "Idempotency key is required")
        @Size(max = 128, message = "Idempotency key must not exceed 128 characters")
        private String idempotencyKey;

        public UUID getAccountId() {
            return accountId;
        }

        public void setAccountId(UUID accountId) {
            this.accountId = accountId;
        }

        public Long getAmountCents() {
            return amountCents;
        }

        public void setAmountCents(Long amountCents) {
            this.amountCents = amountCents;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        public void setIdempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
        }
    }
}
