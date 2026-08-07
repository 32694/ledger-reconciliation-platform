package io.github.user32694.ledgerplatform.ledger.web;

import io.github.user32694.ledgerplatform.ledger.LedgerApi;
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
        model.addAttribute("transactions", ledgerApi.findRecentTransactions(100));
        if (htmx) {
            return "admin/ledger :: ledgerTable";
        }
        model.addAttribute("activeNav", "ledger");
        return "admin/ledger";
    }
}
