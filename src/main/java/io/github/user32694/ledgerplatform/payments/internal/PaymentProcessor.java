package io.github.user32694.ledgerplatform.payments.internal;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditCommand;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import io.github.user32694.ledgerplatform.ledger.EntrySide;
import io.github.user32694.ledgerplatform.ledger.Journal;
import io.github.user32694.ledgerplatform.ledger.JournalEntry;
import io.github.user32694.ledgerplatform.ledger.LedgerApi;
import io.github.user32694.ledgerplatform.ledger.LedgerBalanceLimitExceededException;
import io.github.user32694.ledgerplatform.ledger.LedgerInsufficientFundsException;
import io.github.user32694.ledgerplatform.ledger.Money;
import io.github.user32694.ledgerplatform.messaging.EventType;
import io.github.user32694.ledgerplatform.messaging.OutboxApi;
import io.github.user32694.ledgerplatform.messaging.OutboxCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentProcessor {
    private static final String BALANCE_LIMIT_EXCEEDED = "BALANCE_LIMIT_EXCEEDED";
    private static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private final PaymentInstructionRepository repository;
    private final AccountsApi accountsApi;
    private final LedgerApi ledgerApi;
    private final AuditApi auditApi;
    private final OutboxApi outboxApi;

    PaymentProcessor(
            PaymentInstructionRepository repository,
            AccountsApi accountsApi,
            LedgerApi ledgerApi,
            AuditApi auditApi,
            OutboxApi outboxApi) {
        this.repository = repository;
        this.accountsApi = accountsApi;
        this.ledgerApi = ledgerApi;
        this.auditApi = auditApi;
        this.outboxApi = outboxApi;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PaymentInstructionEntity process(UUID paymentId) {
        /*
         * 处理阶段与提交阶段分离：锁定 PENDING 指令，执行账本 journal，
         * 只有 journal 成功后才把支付改为 SUCCEEDED 并追加审计和 Outbox 事件。
         */
        var payment = repository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment instruction does not exist: " + paymentId));
        if (!"PENDING".equals(payment.status())) {
            return payment;
        }

        try {
            var amount = Money.cny(payment.amountCents());
            // 不同支付类型只是 journal 方向不同，所有类型都复用同一个不可变账本入口。
            switch (payment.paymentType()) {
                case "TOP_UP" -> {
                    var customerWalletId =
                            accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
                    var platformCashId = ledgerApi.createPlatformCashAccount().id();
                    ledgerApi.post(Journal.create(payment.channelReference(), "TOP_UP", List.of(
                            new JournalEntry(platformCashId, EntrySide.DEBIT, amount),
                            new JournalEntry(customerWalletId, EntrySide.CREDIT, amount))));
                }
                case "TRANSFER" -> {
                    var payerWalletId =
                            accountsApi.get(payment.payerAccountId()).ledgerAccountId();
                    var payeeWalletId =
                            accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
                    ledgerApi.post(Journal.create(payment.channelReference(), "TRANSFER", List.of(
                            new JournalEntry(payerWalletId, EntrySide.DEBIT, amount),
                            new JournalEntry(payeeWalletId, EntrySide.CREDIT, amount))));
                }
                case "REFUND" -> {
                    var customerWalletId =
                            accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
                    var platformCashId = ledgerApi.createPlatformCashAccount().id();
                    ledgerApi.post(Journal.create(payment.channelReference(), "REFUND", List.of(
                            new JournalEntry(customerWalletId, EntrySide.DEBIT, amount),
                            new JournalEntry(platformCashId, EntrySide.CREDIT, amount))));
                }
                case "REVERSAL" -> {
                    var payerWalletId =
                            accountsApi.get(payment.payerAccountId()).ledgerAccountId();
                    var payeeWalletId =
                            accountsApi.get(payment.payeeAccountId()).ledgerAccountId();
                    ledgerApi.post(Journal.create(payment.channelReference(), "REVERSAL", List.of(
                            new JournalEntry(payeeWalletId, EntrySide.DEBIT, amount),
                            new JournalEntry(payerWalletId, EntrySide.CREDIT, amount))));
                }
                default -> throw new IllegalStateException(
                        "Unsupported payment type: " + payment.paymentType());
            }
            // 这三步仍在同一事务中：数据库回滚时，支付状态、审计和 Outbox 都一起回滚。
            payment.succeed(Instant.now());
            auditApi.record(paymentAudit(payment, AuditOutcome.SUCCEEDED));
            outboxApi.append(new OutboxCommand(
                    EventType.PAYMENT_SUCCEEDED,
                    "PAYMENT",
                    payment.id().toString(),
                    1,
                    Map.of(
                            "paymentType", payment.paymentType(),
                            "amountCents", payment.amountCents(),
                            "channelReference", payment.channelReference()),
                    payment.occurredAt()));
        } catch (LedgerInsufficientFundsException exception) {
            throw new PaymentRejectedException(payment.id(), INSUFFICIENT_FUNDS);
        } catch (LedgerBalanceLimitExceededException exception) {
            throw new PaymentRejectedException(payment.id(), BALANCE_LIMIT_EXCEEDED);
        }
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PaymentInstructionEntity fail(UUID paymentId, String reason) {
        var payment = repository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment instruction does not exist: " + paymentId));
        if ("PENDING".equals(payment.status())) {
            payment.fail(reason, Instant.now());
            auditApi.record(paymentAudit(payment, AuditOutcome.FAILED));
        }
        return payment;
    }

    private static AuditCommand paymentAudit(
            PaymentInstructionEntity payment, AuditOutcome outcome) {
        AuditAction action = switch (payment.paymentType()) {
            case "TOP_UP" -> AuditAction.PAYMENT_TOP_UP;
            case "TRANSFER" -> AuditAction.PAYMENT_TRANSFER;
            case "REFUND" -> AuditAction.PAYMENT_REFUND;
            case "REVERSAL" -> AuditAction.PAYMENT_REVERSAL;
            default -> throw new IllegalStateException(
                    "Unsupported payment type: " + payment.paymentType());
        };
        String summary = "%s CNY %d %s"
                .formatted(payment.paymentType(), payment.amountCents(), payment.status());
        if (payment.failureReason() != null) {
            summary += " " + payment.failureReason();
        }
        return new AuditCommand(
                null,
                action,
                "PAYMENT",
                payment.id().toString(),
                outcome,
                summary,
                payment.channelReference());
    }

    static final class PaymentRejectedException extends RuntimeException {
        private final UUID paymentId;
        private final String reason;

        PaymentRejectedException(UUID paymentId, String reason) {
            super(reason);
            this.paymentId = paymentId;
            this.reason = reason;
        }

        UUID paymentId() {
            return paymentId;
        }

        String reason() {
            return reason;
        }
    }
}
