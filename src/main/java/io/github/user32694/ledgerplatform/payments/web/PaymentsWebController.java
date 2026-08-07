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
                    "idempotencyKey",
                    "topUp.idempotencyKey.conflict",
                    "该幂等键已被其他请求使用，请更换后重试");
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
            bindingResult.rejectValue(
                    "accountId", "topUp.accountId.invalid", "请选择有效的客户账户");
        } else if (message != null && message.startsWith("Idempotency key ")) {
            bindingResult.rejectValue(
                    "idempotencyKey", "topUp.idempotencyKey.invalid", "幂等键格式无效");
        } else if (message != null && message.startsWith("Amount ")) {
            bindingResult.rejectValue(
                    "amountCents", "topUp.amountCents.invalid", "充值金额必须大于0");
        } else {
            bindingResult.reject("topUp.failed", "充值失败，请检查输入后重试");
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
                        payment.status(),
                        paymentStatusLabel(payment.status())))
                .toList());
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
            String channelReference, BigDecimal amount, String status, String statusLabel) {}

    public static class TopUpForm {
        @NotNull(message = "请选择客户账户")
        private UUID accountId;

        @NotNull(message = "请输入充值金额")
        @Positive(message = "充值金额必须大于0")
        private Long amountCents;

        @NotBlank(message = "请输入幂等键")
        @Size(max = 128, message = "幂等键不能超过128个字符")
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
