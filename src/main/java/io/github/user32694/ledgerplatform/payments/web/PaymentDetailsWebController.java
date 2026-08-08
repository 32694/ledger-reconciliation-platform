package io.github.user32694.ledgerplatform.payments.web;

import io.github.user32694.ledgerplatform.payments.IdempotencyConflictException;
import io.github.user32694.ledgerplatform.payments.PaymentView;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.payments.ReversePaymentCommand;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/admin/payments")
public class PaymentDetailsWebController {
    private final PaymentsApi paymentsApi;

    public PaymentDetailsWebController(PaymentsApi paymentsApi) {
        this.paymentsApi = paymentsApi;
    }

    @GetMapping("/{paymentId}")
    String details(@PathVariable UUID paymentId, Model model) {
        addDetailData(model, requirePayment(paymentId));
        return "admin/payment-detail";
    }

    @GetMapping("/{paymentId}/reverse")
    String reverseForm(
            @PathVariable UUID paymentId, Model model, HttpServletResponse response) {
        PaymentView payment = requirePayment(paymentId);
        Optional<PaymentView> activeReverse = paymentsApi.findActiveReverse(payment.id());
        if (!isEligible(payment, activeReverse)) {
            return rejectIneligible(model, response, payment);
        }
        if (!model.containsAttribute("reverseForm")) {
            model.addAttribute("reverseForm", new ReverseForm());
        }
        addReverseFormData(model, payment);
        return "admin/payment-reverse-form";
    }

    @PostMapping("/{paymentId}/reverse")
    String reverse(
            @PathVariable UUID paymentId,
            @Valid @ModelAttribute("reverseForm") ReverseForm form,
            BindingResult bindingResult,
            Model model,
            HttpServletResponse response) {
        PaymentView payment = requirePayment(paymentId);
        Optional<PaymentView> activeReverse = paymentsApi.findActiveReverse(payment.id());
        if (activeReverse.isPresent()) {
            return redirectTo(activeReverse.orElseThrow());
        }
        if (!isEligible(payment, activeReverse)) {
            return rejectIneligible(model, response, payment);
        }
        if (bindingResult.hasErrors()) {
            addReverseFormData(model, payment);
            return "admin/payment-reverse-form";
        }

        PaymentView reversePayment;
        try {
            reversePayment = paymentsApi.reverse(new ReversePaymentCommand(
                    form.getIdempotencyKey(), payment.id(), form.getReason()));
        } catch (IdempotencyConflictException exception) {
            bindingResult.rejectValue(
                    "idempotencyKey",
                    "reverse.idempotencyKey.conflict",
                    "该幂等键已被其他请求使用，请更换后重试");
            addReverseFormData(model, payment);
            return "admin/payment-reverse-form";
        } catch (IllegalArgumentException exception) {
            bindingResult.reject("reverse.invalid", "退款或冲正失败，请检查输入后重试");
            addReverseFormData(model, payment);
            return "admin/payment-reverse-form";
        }

        if ("FAILED".equals(reversePayment.status())) {
            rejectFailedReverse(bindingResult, reversePayment.failureReason());
            addReverseFormData(model, payment);
            return "admin/payment-reverse-form";
        }
        return redirectTo(reversePayment);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ModelAndView notFound(ResponseStatusException exception) {
        if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
            throw exception;
        }
        return new ModelAndView("error/404", HttpStatus.NOT_FOUND);
    }

    private String rejectIneligible(
            Model model, HttpServletResponse response, PaymentView payment) {
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("operationError", "该交易不可退款或冲正");
        addDetailData(model, payment);
        return "admin/payment-detail";
    }

    private void addDetailData(Model model, PaymentView payment) {
        Optional<PaymentView> activeReverse = paymentsApi.findActiveReverse(payment.id());
        Optional<PaymentView> succeededReverse = activeReverse
                .filter(reverse -> "SUCCEEDED".equals(reverse.status()));
        model.addAttribute("payment", payment);
        model.addAttribute("paymentTypeLabel", paymentTypeLabel(payment.type()));
        model.addAttribute("paymentStatusLabel", paymentStatusLabel(payment.status()));
        model.addAttribute("paymentFailureLabel", failureReasonLabel(payment.failureReason()));
        model.addAttribute("paymentAmount", BigDecimal.valueOf(payment.amountCents(), 2));
        model.addAttribute("reversePayment", succeededReverse.orElse(null));
        model.addAttribute("reverseProcessingLabel", activeReverse
                .filter(reverse -> "PENDING".equals(reverse.status()))
                .map(reverse -> processingLabel(reverse.type()))
                .orElse(null));
        model.addAttribute("eligible", isEligible(payment, activeReverse));
        model.addAttribute("reverseActionLabel", reverseActionLabel(payment.type()));
        model.addAttribute("completionLabel", completionLabel(payment.type()));
        model.addAttribute("reverseCompletionLabel", succeededReverse
                .map(reverse -> completionLabel(reverse.type()))
                .orElse(null));
        model.addAttribute("activeNav", "payments");
    }

    private void addReverseFormData(Model model, PaymentView payment) {
        model.addAttribute("payment", payment);
        model.addAttribute("paymentTypeLabel", paymentTypeLabel(payment.type()));
        model.addAttribute("paymentAmount", BigDecimal.valueOf(payment.amountCents(), 2));
        model.addAttribute("reverseActionLabel", reverseActionLabel(payment.type()));
        model.addAttribute("activeNav", "payments");
    }

    private PaymentView requirePayment(UUID paymentId) {
        try {
            return paymentsApi.get(paymentId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "交易不存在");
        }
    }

    private static boolean isEligible(
            PaymentView payment, Optional<PaymentView> activeReverse) {
        return "SUCCEEDED".equals(payment.status())
                && ("TOP_UP".equals(payment.type()) || "TRANSFER".equals(payment.type()))
                && activeReverse.isEmpty();
    }

    private static String redirectTo(PaymentView payment) {
        return "redirect:/admin/payments/" + payment.id();
    }

    private static void rejectFailedReverse(
            BindingResult bindingResult, String failureReason) {
        if ("INSUFFICIENT_FUNDS".equals(failureReason)) {
            bindingResult.reject(
                    "reverse.insufficientFunds",
                    "可退回余额不足，请补足资金后使用新幂等键重试");
        } else if ("BALANCE_LIMIT_EXCEEDED".equals(failureReason)) {
            bindingResult.reject("reverse.balanceLimit", "账户余额超出系统支持范围");
        } else {
            bindingResult.reject("reverse.failed", "退款或冲正失败，请检查后重试");
        }
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

    private static String paymentStatusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "处理中";
            case "SUCCEEDED" -> "成功";
            case "FAILED" -> "失败";
            default -> status;
        };
    }

    private static String failureReasonLabel(String failureReason) {
        if (failureReason == null) {
            return null;
        }
        return switch (failureReason) {
            case "INSUFFICIENT_FUNDS" -> "余额不足";
            case "BALANCE_LIMIT_EXCEEDED" -> "账户余额超出系统支持范围";
            default -> "处理失败，请查看审计日志";
        };
    }

    private static String reverseActionLabel(String type) {
        return "TOP_UP".equals(type) ? "发起全额退款" : "发起全额冲正";
    }

    private static String completionLabel(String type) {
        return switch (type) {
            case "REFUND" -> "全额退款";
            case "REVERSAL" -> "全额冲正";
            default -> null;
        };
    }

    private static String processingLabel(String type) {
        return switch (type) {
            case "REFUND" -> "全额退款处理中";
            case "REVERSAL" -> "全额冲正处理中";
            default -> "反向交易处理中";
        };
    }

    public static class ReverseForm {
        @NotBlank(message = "请输入退款或冲正原因")
        @Size(max = 500, message = "退款或冲正原因不能超过500个字符")
        private String reason;

        @NotBlank(message = "请输入幂等键")
        @Size(max = 128, message = "幂等键不能超过128个字符")
        private String idempotencyKey;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        public void setIdempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
        }
    }
}
