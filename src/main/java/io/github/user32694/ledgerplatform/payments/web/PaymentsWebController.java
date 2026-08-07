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
import java.math.BigDecimal;
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
        addRecentPayments(model);
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
        } catch (IdempotencyConflictException exception) {
            bindingResult.rejectValue(
                    "idempotencyKey", "topUp.idempotencyKey.conflict", exception.getMessage());
            addReferenceData(model);
            return "admin/topup-form";
        } catch (IllegalArgumentException exception) {
            rejectInvalidCommand(bindingResult, exception);
            addReferenceData(model);
            return "admin/topup-form";
        }
        return "redirect:/admin/payments/top-up";
    }

    private static void rejectInvalidCommand(
            BindingResult bindingResult, IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message != null && (message.startsWith("Customer account does not exist:")
                || message.equals("Payee account id is required"))) {
            bindingResult.rejectValue("accountId", "topUp.accountId.invalid", message);
        } else if (message != null && message.startsWith("Idempotency key ")) {
            bindingResult.rejectValue("idempotencyKey", "topUp.idempotencyKey.invalid", message);
        } else if (message != null && message.startsWith("Amount ")) {
            bindingResult.rejectValue("amountCents", "topUp.amountCents.invalid", message);
        } else {
            bindingResult.reject("topUp.failed", message);
        }
    }

    private void addReferenceData(Model model) {
        model.addAttribute("accounts", accountsApi.findAll());
        addRecentPayments(model);
        model.addAttribute("activeNav", "payments");
    }

    private void addRecentPayments(Model model) {
        model.addAttribute("recentPayments", paymentsApi.findRecent(20).stream()
                .map(payment -> new PaymentRow(
                        payment.channelReference(),
                        BigDecimal.valueOf(payment.amountCents(), 2),
                        payment.status()))
                .toList());
    }

    public record PaymentRow(String channelReference, BigDecimal amount, String status) {}

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
