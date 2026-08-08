package io.github.user32694.ledgerplatform.payments.web;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.IdempotencyConflictException;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.TopUpCommand;
import io.github.user32694.ledgerplatform.payments.TransferCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.PropertyAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DefaultBindingErrorProcessor;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
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

    @InitBinder("topUpForm")
    void bindTopUpForm(WebDataBinder binder) {
        binder.setBindingErrorProcessor(new TopUpBindingErrorProcessor());
    }

    @InitBinder("transferForm")
    void bindTransferForm(WebDataBinder binder) {
        binder.setBindingErrorProcessor(new TransferBindingErrorProcessor());
    }

    @GetMapping("/top-up")
    String topUpForm(Model model) {
        if (!model.containsAttribute("topUpForm")) {
            model.addAttribute("topUpForm", new TopUpForm());
        }
        addReferenceData(model, "/admin/payments/top-up");
        return "admin/topup-form";
    }

    @GetMapping("/recent")
    String recentPayments(Model model) {
        addRecentPayments(model);
        model.addAttribute("paymentHistoryRefreshUrl", "/admin/payments/top-up");
        return "admin/payment-history :: recentPaymentsTable";
    }

    @GetMapping("/transfer")
    String transferForm(Model model) {
        if (!model.containsAttribute("transferForm")) {
            model.addAttribute("transferForm", new TransferForm());
        }
        addReferenceData(model, "/admin/payments/transfer");
        return "admin/transfer-form";
    }

    @PostMapping("/top-up")
    String topUp(
            @Valid @ModelAttribute("topUpForm") TopUpForm form,
            BindingResult bindingResult,
            Model model) {
        if (bindingResult.hasErrors()) {
            addReferenceData(model, "/admin/payments/top-up");
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
            addReferenceData(model, "/admin/payments/top-up");
            return "admin/topup-form";
        } catch (IllegalArgumentException exception) {
            rejectInvalidCommand(bindingResult, exception);
            addReferenceData(model, "/admin/payments/top-up");
            return "admin/topup-form";
        }
        return "redirect:/admin/payments/top-up";
    }

    @PostMapping("/transfer")
    String transfer(
            @Valid @ModelAttribute("transferForm") TransferForm form,
            BindingResult bindingResult,
            Model model) {
        if (bindingResult.hasErrors()) {
            addReferenceData(model, "/admin/payments/transfer");
            return "admin/transfer-form";
        }
        try {
            var payment = paymentsApi.transfer(new TransferCommand(
                    form.getIdempotencyKey(),
                    form.getPayerAccountId(),
                    form.getPayeeAccountId(),
                    form.getAmountCents()));
            if ("FAILED".equals(payment.status())) {
                rejectFailedTransfer(bindingResult, payment.failureReason());
                addReferenceData(model, "/admin/payments/transfer");
                return "admin/transfer-form";
            }
        } catch (IdempotencyConflictException exception) {
            bindingResult.rejectValue(
                    "idempotencyKey",
                    "transfer.idempotencyKey.conflict",
                    "该幂等键已被其他请求使用，请更换后重试");
            addReferenceData(model, "/admin/payments/transfer");
            return "admin/transfer-form";
        } catch (IllegalArgumentException exception) {
            rejectInvalidTransfer(bindingResult, exception);
            addReferenceData(model, "/admin/payments/transfer");
            return "admin/transfer-form";
        }
        return "redirect:/admin/payments/transfer";
    }

    private static void rejectFailedTransfer(BindingResult bindingResult, String failureReason) {
        if ("INSUFFICIENT_FUNDS".equals(failureReason)) {
            bindingResult.reject("transfer.insufficientFunds", "付款账户余额不足");
        } else if ("BALANCE_LIMIT_EXCEEDED".equals(failureReason)) {
            bindingResult.reject("transfer.balanceLimit", "账户余额超出系统支持范围");
        } else {
            bindingResult.reject("transfer.failed", "转账失败，请检查输入后重试");
        }
    }

    private static void rejectInvalidTransfer(
            BindingResult bindingResult, IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message != null && message.startsWith("Payer account")) {
            bindingResult.rejectValue(
                    "payerAccountId", "transfer.payerAccountId.invalid", "请选择有效的付款账户");
        } else if (message != null && message.startsWith("Payee account")) {
            bindingResult.rejectValue(
                    "payeeAccountId", "transfer.payeeAccountId.invalid", "请选择有效的收款账户");
        } else if (message != null && message.contains("must be different")) {
            bindingResult.reject("transfer.sameAccount", "付款账户和收款账户不能相同");
        } else if (message != null && message.startsWith("Idempotency key ")) {
            bindingResult.rejectValue(
                    "idempotencyKey", "transfer.idempotencyKey.invalid", "幂等键格式无效");
        } else if (message != null && message.startsWith("Amount ")) {
            bindingResult.rejectValue(
                    "amountCents", "transfer.amountCents.invalid", "转账金额必须大于0");
        } else {
            bindingResult.reject("transfer.failed", "转账失败，请检查输入后重试");
        }
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

    private void addReferenceData(Model model, String paymentHistoryRefreshUrl) {
        model.addAttribute("accounts", accountsApi.findAll());
        addRecentPayments(model);
        model.addAttribute("paymentHistoryRefreshUrl", paymentHistoryRefreshUrl);
        model.addAttribute("activeNav", "payments");
    }

    private void addRecentPayments(Model model) {
        model.addAttribute("recentPayments", paymentsApi.findRecent(20).stream()
                .map(payment -> new PaymentRow(
                        payment.id(),
                        payment.channelReference(),
                        payment.type(),
                        paymentTypeLabel(payment.type()),
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

    private static String paymentTypeLabel(String type) {
        return switch (type) {
            case "TOP_UP" -> "充值";
            case "TRANSFER" -> "转账";
            case "REFUND" -> "退款";
            case "REVERSAL" -> "冲正";
            default -> type;
        };
    }

    public record PaymentRow(
            UUID id,
            String channelReference,
            String type,
            String typeLabel,
            BigDecimal amount,
            String status,
            String statusLabel) {}

    private static final class TopUpBindingErrorProcessor extends DefaultBindingErrorProcessor {
        @Override
        public void processPropertyAccessException(
                PropertyAccessException exception, BindingResult bindingResult) {
            String field = exception.getPropertyName();
            String message;
            if ("accountId".equals(field)) {
                message = "请选择有效的客户账户";
            } else if ("amountCents".equals(field)) {
                message = "请输入充值金额";
            } else {
                super.processPropertyAccessException(exception, bindingResult);
                return;
            }
            FieldError error = new FieldError(
                    bindingResult.getObjectName(),
                    field,
                    exception.getValue(),
                    true,
                    bindingResult.resolveMessageCodes(exception.getErrorCode(), field),
                    getArgumentsForBindError(bindingResult.getObjectName(), field),
                    message);
            error.wrap(exception);
            bindingResult.addError(error);
        }
    }

    private static final class TransferBindingErrorProcessor extends DefaultBindingErrorProcessor {
        @Override
        public void processPropertyAccessException(
                PropertyAccessException exception, BindingResult bindingResult) {
            String field = exception.getPropertyName();
            String message;
            if ("payerAccountId".equals(field)) {
                message = "请选择有效的付款账户";
            } else if ("payeeAccountId".equals(field)) {
                message = "请选择有效的收款账户";
            } else if ("amountCents".equals(field)) {
                message = "请输入转账金额";
            } else {
                super.processPropertyAccessException(exception, bindingResult);
                return;
            }
            FieldError error = new FieldError(
                    bindingResult.getObjectName(),
                    field,
                    exception.getValue(),
                    true,
                    bindingResult.resolveMessageCodes(exception.getErrorCode(), field),
                    getArgumentsForBindError(bindingResult.getObjectName(), field),
                    message);
            error.wrap(exception);
            bindingResult.addError(error);
        }
    }

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

    public static class TransferForm {
        @NotNull(message = "请选择付款账户")
        private UUID payerAccountId;

        @NotNull(message = "请选择收款账户")
        private UUID payeeAccountId;

        @NotNull(message = "请输入转账金额")
        @Positive(message = "转账金额必须大于0")
        private Long amountCents;

        @NotBlank(message = "请输入幂等键")
        @Size(max = 128, message = "幂等键不能超过128个字符")
        private String idempotencyKey;

        public UUID getPayerAccountId() {
            return payerAccountId;
        }

        public void setPayerAccountId(UUID payerAccountId) {
            this.payerAccountId = payerAccountId;
        }

        public UUID getPayeeAccountId() {
            return payeeAccountId;
        }

        public void setPayeeAccountId(UUID payeeAccountId) {
            this.payeeAccountId = payeeAccountId;
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
