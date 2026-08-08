package io.github.user32694.ledgerplatform.ledger.web;

import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/ledger")
public class LedgerWebController {
    private final LedgerApi ledgerApi;

    public LedgerWebController(LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    @GetMapping
    String ledger(
            @RequestHeader(name = "HX-Request", required = false) boolean htmx, Model model) {
        model.addAttribute("transactions", ledgerApi.findRecentTransactions(100).stream()
                .map(transaction -> new LedgerRow(
                        transaction.id(),
                        transaction.businessReference(),
                        transaction.transactionType(),
                        transactionTypeLabel(transaction.transactionType()),
                        transaction.occurredAt(),
                        BigDecimal.valueOf(transaction.amountCents(), 2)))
                .toList());
        if (htmx) {
            return "admin/ledger :: ledgerTable";
        }
        model.addAttribute("activeNav", "ledger");
        return "admin/ledger";
    }

    private static String transactionTypeLabel(String transactionType) {
        return switch (transactionType) {
            case "TOP_UP" -> "充值";
            case "TRANSFER" -> "转账";
            case "REFUND" -> "充值退款";
            case "REVERSAL" -> "转账冲正";
            default -> transactionType;
        };
    }

    public record LedgerRow(
            UUID id,
            String businessReference,
            String transactionType,
            String transactionTypeLabel,
            Instant occurredAt,
            BigDecimal amount) {}
}
