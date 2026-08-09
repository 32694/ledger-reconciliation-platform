package io.github.user32694.ledgerplatform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditEventView;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.test.context.jdbc.SqlMergeMode.MergeMode;

@ApplicationModuleTest(extraIncludes = {"accounts", "ledger", "audit"})
@ActiveProfiles("test")
@SqlMergeMode(MergeMode.MERGE)
@Sql(statements = {
    "DELETE FROM audit.audit_event",
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
    "DELETE FROM audit.audit_event",
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TopUpModuleTest {
    @Autowired PaymentsApi paymentsApi;
    @Autowired io.github.user32694.ledgerplatform.accounts.AccountsApi accountsApi;
    @Autowired AuditApi auditApi;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void exposesLinkedReversePaymentFields() {
        var originalId = UUID.randomUUID();
        var view = new PaymentView(
                UUID.randomUUID(), "REFUND-1", "REFUND", null, UUID.randomUUID(),
                500, "PENDING", null, originalId, "Customer refund", null);

        assertThat(view.originalPaymentId()).isEqualTo(originalId);
        assertThat(view.operationReason()).isEqualTo("Customer refund");
    }

    @Test
    void preservesLegacyPaymentViewConstructors() {
        var id = UUID.randomUUID();
        var payeeAccountId = UUID.randomUUID();
        var occurredAt = Instant.now();

        var legacyFull = new PaymentView(
                id, "TOPUP-1", "TOP_UP", null, payeeAccountId,
                500, "SUCCEEDED", null, occurredAt);
        var legacyShort = new PaymentView(
                id, "TOPUP-1", "TOP_UP", 500, "SUCCEEDED", null, occurredAt);

        assertThat(legacyFull.originalPaymentId()).isNull();
        assertThat(legacyFull.operationReason()).isNull();
        assertThat(legacyShort.originalPaymentId()).isNull();
        assertThat(legacyShort.operationReason()).isNull();
    }

    @Test
    void postsTopUpOnceForRepeatedRequest() {
        var account = accountsApi.create("Top Up Customer");
        var command = new TopUpCommand("idem-topup-1", account.id(), 5000);

        var first = paymentsApi.topUp(command);
        var repeated = paymentsApi.topUp(command);

        assertThat(first.id()).isEqualTo(repeated.id());
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.payerAccountId()).isNull();
        assertThat(first.payeeAccountId()).isEqualTo(account.id());
        assertThat(first.originalPaymentId()).isNull();
        assertThat(first.operationReason()).isNull();
        assertThat(accountsApi.balance(account.id()).cents()).isEqualTo(5000);
        assertThat(auditApi.findRecent(AuditAction.PAYMENT_TOP_UP, null, 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.aggregateType()).isEqualTo("PAYMENT");
                    assertThat(event.aggregateId()).isEqualTo(first.id().toString());
                    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                    assertThat(event.summary()).isEqualTo("TOP_UP CNY 5000 SUCCEEDED");
                    assertThat(event.correlationReference()).isEqualTo(first.channelReference());
                });
    }

    @Test
    void findsOnlySuccessfulTopUpsInInclusiveCompletionRange() {
        var account = accountsApi.create("Reconciliation Candidate");
        var first = paymentsApi.topUp(new TopUpCommand("candidate-1", account.id(), 100));
        var second = paymentsApi.topUp(new TopUpCommand("candidate-2", account.id(), 200));

        assertThat(paymentsApi.findSucceededTopUps(first.occurredAt(), second.occurredAt()))
                .extracting(PaymentView::id)
                .containsExactly(first.id(), second.id());
        assertThatThrownBy(() -> paymentsApi.findSucceededTopUps(
                second.occurredAt(), first.occurredAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Start must not be after end");
    }

    @Test
    void rejectsIdempotencyKeyWithDifferentPayload() {
        var account = accountsApi.create("Conflict Customer");
        paymentsApi.topUp(new TopUpCommand("idem-conflict-1", account.id(), 5000));

        assertThatThrownBy(() -> paymentsApi.topUp(
                new TopUpCommand("idem-conflict-1", account.id(), 6000)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectsConflictingPayloadBeforeLookingUpPayee() {
        var account = accountsApi.create("Conflict Priority Customer");
        paymentsApi.topUp(new TopUpCommand("idem-priority-1", account.id(), 5000));

        assertThatThrownBy(() -> paymentsApi.topUp(
                new TopUpCommand("idem-priority-1", UUID.randomUUID(), 6000)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectsUnknownPayeeWithoutCreatingInstruction() {
        assertThatThrownBy(() -> paymentsApi.topUp(new TopUpCommand(
                "idem-unknown-1", UUID.randomUUID(), 5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");

        assertThat(paymentsApi.findRecent(1)).isEmpty();
    }

    @Test
    void recordsFailedTopUpWhenProcessingIsRejected() {
        var account = accountsApi.create("Overflow Customer");
        paymentsApi.topUp(new TopUpCommand("idem-max-1", account.id(), Long.MAX_VALUE));

        var failed = paymentsApi.topUp(new TopUpCommand("idem-overflow-1", account.id(), 1));

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("BALANCE_LIMIT_EXCEEDED");
        assertThat(accountsApi.balance(account.id()).cents()).isEqualTo(Long.MAX_VALUE);
        assertThat(paymentsApi.findRecent(1)).containsExactly(failed);
    }

    @Test
    void transfersFundsBetweenCustomerAccounts() {
        var payer = accountsApi.create("Transfer Payer");
        var payee = accountsApi.create("Transfer Payee");
        paymentsApi.topUp(new TopUpCommand("fund-transfer-payer", payer.id(), 5000));

        var transfer = paymentsApi.transfer(new TransferCommand(
                "transfer-success-1", payer.id(), payee.id(), 1200));

        assertThat(transfer.type()).isEqualTo("TRANSFER");
        assertThat(transfer.payerAccountId()).isEqualTo(payer.id());
        assertThat(transfer.payeeAccountId()).isEqualTo(payee.id());
        assertThat(transfer.status()).isEqualTo("SUCCEEDED");
        assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(3800);
        assertThat(accountsApi.balance(payee.id()).cents()).isEqualTo(1200);
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_entry entry
                JOIN ledger.ledger_transaction transaction ON transaction.id = entry.transaction_id
                WHERE transaction.business_reference = ?
                """, transfer.channelReference())).isEqualTo(2);
    }

    @Test
    void rejectsTransferToTheSameAccountWithoutCreatingInstruction() {
        var account = accountsApi.create("Self Transfer Customer");

        assertThatThrownBy(() -> paymentsApi.transfer(new TransferCommand(
                "transfer-self-1", account.id(), account.id(), 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");

        assertThat(paymentsApi.findRecent(1)).isEmpty();
    }

    @Test
    void rejectsUnknownTransferPartyWithoutCreatingInstruction() {
        var payee = accountsApi.create("Known Transfer Payee");

        assertThatThrownBy(() -> paymentsApi.transfer(new TransferCommand(
                "transfer-unknown-party-1", UUID.randomUUID(), payee.id(), 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");

        assertThat(paymentsApi.findRecent(1)).isEmpty();
    }

    @Test
    void recordsFailedTransferWhenPayerHasInsufficientFunds() {
        var payer = accountsApi.create("Insufficient Payer");
        var payee = accountsApi.create("Insufficient Payee");
        paymentsApi.topUp(new TopUpCommand("fund-insufficient-payer", payer.id(), 500));

        var transfer = paymentsApi.transfer(new TransferCommand(
                "transfer-insufficient-1", payer.id(), payee.id(), 600));
        var replay = paymentsApi.transfer(new TransferCommand(
                "transfer-insufficient-1", payer.id(), payee.id(), 600));

        assertThat(replay.id()).isEqualTo(transfer.id());
        assertThat(transfer.status()).isEqualTo("FAILED");
        assertThat(transfer.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(500);
        assertThat(accountsApi.balance(payee.id()).cents()).isZero();
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, transfer.channelReference())).isZero();
        assertThat(auditApi.findRecent(AuditAction.PAYMENT_TRANSFER, null, 100))
                .singleElement()
                .extracting(
                        AuditEventView::aggregateType,
                        AuditEventView::aggregateId,
                        AuditEventView::outcome,
                        AuditEventView::summary,
                        AuditEventView::correlationReference)
                .containsExactly(
                        "PAYMENT",
                        transfer.id().toString(),
                        AuditOutcome.FAILED,
                        "TRANSFER CNY 600 FAILED INSUFFICIENT_FUNDS",
                        transfer.channelReference());
    }

    @Test
    void replaysTheSameTransferWithoutMovingFundsAgain() {
        var payer = accountsApi.create("Replay Payer");
        var payee = accountsApi.create("Replay Payee");
        paymentsApi.topUp(new TopUpCommand("fund-replay-payer", payer.id(), 1000));
        var command = new TransferCommand("transfer-replay-1", payer.id(), payee.id(), 400);

        var first = paymentsApi.transfer(command);
        var replay = paymentsApi.transfer(command);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(600);
        assertThat(accountsApi.balance(payee.id()).cents()).isEqualTo(400);
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, first.channelReference())).isOne();
    }

    @Test
    void rejectsTransferIdempotencyKeyWithDifferentPayload() {
        var payer = accountsApi.create("Transfer Conflict Payer");
        var payee = accountsApi.create("Transfer Conflict Payee");
        paymentsApi.topUp(new TopUpCommand("fund-transfer-conflict", payer.id(), 1000));
        paymentsApi.transfer(new TransferCommand(
                "transfer-conflict-1", payer.id(), payee.id(), 400));

        assertThatThrownBy(() -> paymentsApi.transfer(new TransferCommand(
                "transfer-conflict-1", payer.id(), payee.id(), 500)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void preventsConcurrentTransfersFromOverdrawingThePayer() throws Exception {
        var payer = accountsApi.create("Concurrent Transfer Payer");
        var firstPayee = accountsApi.create("Concurrent Transfer Payee One");
        var secondPayee = accountsApi.create("Concurrent Transfer Payee Two");
        paymentsApi.topUp(new TopUpCommand("fund-concurrent-transfer", payer.id(), 1000));
        var first = new TransferCommand(
                "concurrent-transfer-1", payer.id(), firstPayee.id(), 700);
        var second = new TransferCommand(
                "concurrent-transfer-2", payer.id(), secondPayee.id(), 700);

        var attempts = transferConcurrently(first, second);

        assertThat(attempts).extracting(Attempt::error).containsOnlyNulls();
        assertThat(attempts).extracting(attempt -> attempt.payment().status())
                .containsExactlyInAnyOrder("SUCCEEDED", "FAILED");
        assertThat(attempts).filteredOn(attempt -> "FAILED".equals(attempt.payment().status()))
                .extracting(attempt -> attempt.payment().failureReason())
                .containsExactly("INSUFFICIENT_FUNDS");
        assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(300);
        assertThat(accountsApi.balance(firstPayee.id()).cents()
                + accountsApi.balance(secondPayee.id()).cents()).isEqualTo(700);
    }

    @Test
    void acceptsIdempotencyKeyAt128UnicodeCodePoints() {
        var account = accountsApi.create("Unicode Key Customer");
        String supplementaryCharacter = new String(Character.toChars(0x10400));
        String key = supplementaryCharacter.repeat(128);

        var payment = paymentsApi.topUp(new TopUpCommand(key, account.id(), 5000));

        assertThat(payment.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsIdempotencyKeyLongerThan128UnicodeCodePoints() {
        var account = accountsApi.create("Long Unicode Key Customer");
        String supplementaryCharacter = new String(Character.toChars(0x10400));

        assertThatThrownBy(() -> paymentsApi.topUp(new TopUpCommand(
                supplementaryCharacter.repeat(129), account.id(), 5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    @Test
    void rejectsControlCharactersInIdempotencyKey() {
        var account = accountsApi.create("Control Key Customer");

        assertThatThrownBy(() -> paymentsApi.topUp(new TopUpCommand(
                "nul\0key", account.id(), 5000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentsApi.topUp(new TopUpCommand(
                "control\u001Fkey", account.id(), 5000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsIdempotencyKeyOpaque() {
        var account = accountsApi.create("Opaque Key Customer");
        String key = "  opaque-key  ";

        paymentsApi.topUp(new TopUpCommand(key, account.id(), 5000));

        assertThat(count("""
                SELECT COUNT(*)
                FROM payments.payment_instruction
                WHERE idempotency_key = ?
                """, key)).isOne();
    }

    @Test
    void processesConcurrentRepeatedRequestOnce() throws Exception {
        var account = accountsApi.create("Concurrent Repeat Customer");
        var command = new TopUpCommand("idem-concurrent-repeat-1", account.id(), 5000);

        var attempts = topUpConcurrently(command, command);

        assertThat(attempts).extracting(Attempt::error).containsOnlyNulls();
        assertThat(attempts).extracting(attempt -> attempt.payment().id())
                .containsOnly(attempts.get(0).payment().id());
        var payment = attempts.get(0).payment();
        assertThat(payment.status()).isEqualTo("SUCCEEDED");
        assertThat(accountsApi.balance(account.id()).cents()).isEqualTo(5000);
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, payment.channelReference())).isOne();
        assertThat(count("""
                SELECT COUNT(*)
                FROM payments.payment_instruction
                WHERE idempotency_key = ?
                """, command.idempotencyKey())).isOne();
    }

    @Test
    void acceptsOneConcurrentPayloadAndRejectsTheOther() throws Exception {
        var account = accountsApi.create("Concurrent Conflict Customer");
        var first = new TopUpCommand("idem-concurrent-conflict-1", account.id(), 5000);
        var second = new TopUpCommand("idem-concurrent-conflict-1", account.id(), 6000);

        var attempts = topUpConcurrently(first, second);
        var successes = attempts.stream()
                .filter(attempt -> attempt.error() == null)
                .map(Attempt::payment)
                .toList();
        var failures = attempts.stream()
                .map(Attempt::error)
                .filter(error -> error != null)
                .toList();

        assertThat(successes).singleElement().satisfies(payment ->
                assertThat(payment.status()).isEqualTo("SUCCEEDED"));
        assertThat(failures).singleElement().isInstanceOf(IdempotencyConflictException.class);
        var winner = successes.get(0);
        assertThat(accountsApi.balance(account.id()).cents()).isEqualTo(winner.amountCents());
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, winner.channelReference())).isOne();
        assertThat(count("""
                SELECT COUNT(*)
                FROM payments.payment_instruction
                WHERE idempotency_key = ? AND status = 'SUCCEEDED'
                """, first.idempotencyKey())).isOne();
    }

    @Test
    void validatesRecentPaymentLimit() {
        assertThatThrownBy(() -> paymentsApi.findRecent(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentsApi.findRecent(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(paymentsApi.findRecent(1)).isEmpty();
        assertThat(paymentsApi.findRecent(100)).isEmpty();
    }

    @Test
    void readsLinkedActiveReversePayment() {
        var account = accountsApi.create("Linked Refund Customer");
        var original = paymentsApi.topUp(
                new TopUpCommand("linked-refund-source", account.id(), 500));
        var refundId = UUID.randomUUID();
        insertReverseFixture(
                refundId, "linked-refund", original.id(), account.id(), "PENDING", null);

        assertThat(paymentsApi.get(refundId))
                .extracting(
                        PaymentView::type,
                        PaymentView::originalPaymentId,
                        PaymentView::operationReason)
                .containsExactly("REFUND", original.id(), "Customer refund");
        assertThat(paymentsApi.findActiveReverse(original.id()))
                .map(PaymentView::id)
                .contains(refundId);
    }

    @Test
    void doesNotFindFailedReverseAsActive() {
        var account = accountsApi.create("Failed Refund Customer");
        var original = paymentsApi.topUp(
                new TopUpCommand("failed-refund-source", account.id(), 500));
        insertReverseFixture(
                UUID.randomUUID(), "failed-refund", original.id(), account.id(),
                "FAILED", "PROCESSING_REJECTED");

        assertThat(paymentsApi.findActiveReverse(original.id())).isEmpty();
    }

    @Test
    void refundsASuccessfulTopUpInFullWithoutChangingTheOriginal() {
        var account = accountsApi.create("Refund Customer");
        var original = paymentsApi.topUp(
                new TopUpCommand("refund-source-1", account.id(), 5000));
        var originalJournal = journalSnapshot(original.channelReference());

        var refund = paymentsApi.reverse(new ReversePaymentCommand(
                "refund-success-1", original.id(), "  Customer requested refund  "));

        assertThat(refund.type()).isEqualTo("REFUND");
        assertThat(refund.originalPaymentId()).isEqualTo(original.id());
        assertThat(refund.amountCents()).isEqualTo(5000);
        assertThat(refund.status()).isEqualTo("SUCCEEDED");
        assertThat(refund.operationReason()).isEqualTo("Customer requested refund");
        assertThat(refund.payerAccountId()).isNull();
        assertThat(refund.payeeAccountId()).isEqualTo(account.id());
        assertThat(refund.channelReference()).startsWith("REFUND-");
        assertThat(accountsApi.balance(account.id()).cents()).isZero();
        assertThat(journalType(refund.channelReference())).isEqualTo("REFUND");
        assertThat(journalEntries(refund.channelReference()))
                .extracting(JournalEntrySnapshot::accountId, JournalEntrySnapshot::side,
                        JournalEntrySnapshot::amountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                account.ledgerAccountId(), "DEBIT", 5000L),
                        org.assertj.core.groups.Tuple.tuple(
                                platformCashAccountId(), "CREDIT", 5000L));
        assertThat(paymentsApi.get(original.id())).isEqualTo(original);
        assertThat(journalSnapshot(original.channelReference())).isEqualTo(originalJournal);
        assertSuccessfulPaymentAudit(AuditAction.PAYMENT_REFUND, refund);
    }

    @Test
    void reversesASuccessfulTransferInFullWithoutChangingTheOriginal() {
        var payer = accountsApi.create("Reversal Payer");
        var payee = accountsApi.create("Reversal Payee");
        paymentsApi.topUp(new TopUpCommand("reversal-funding-1", payer.id(), 5000));
        var original = paymentsApi.transfer(new TransferCommand(
                "reversal-source-1", payer.id(), payee.id(), 1200));
        var originalJournal = journalSnapshot(original.channelReference());

        var reversal = paymentsApi.reverse(new ReversePaymentCommand(
                "reversal-success-1", original.id(), "Transfer entered twice"));

        assertThat(reversal.type()).isEqualTo("REVERSAL");
        assertThat(reversal.originalPaymentId()).isEqualTo(original.id());
        assertThat(reversal.amountCents()).isEqualTo(1200);
        assertThat(reversal.status()).isEqualTo("SUCCEEDED");
        assertThat(reversal.operationReason()).isEqualTo("Transfer entered twice");
        assertThat(reversal.payerAccountId()).isEqualTo(payer.id());
        assertThat(reversal.payeeAccountId()).isEqualTo(payee.id());
        assertThat(reversal.channelReference()).startsWith("REVERSAL-");
        assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(5000);
        assertThat(accountsApi.balance(payee.id()).cents()).isZero();
        assertThat(journalType(reversal.channelReference())).isEqualTo("REVERSAL");
        assertThat(journalEntries(reversal.channelReference()))
                .extracting(JournalEntrySnapshot::accountId, JournalEntrySnapshot::side,
                        JournalEntrySnapshot::amountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                payee.ledgerAccountId(), "DEBIT", 1200L),
                        org.assertj.core.groups.Tuple.tuple(
                                payer.ledgerAccountId(), "CREDIT", 1200L));
        assertThat(paymentsApi.get(original.id())).isEqualTo(original);
        assertThat(journalSnapshot(original.channelReference())).isEqualTo(originalJournal);
        assertSuccessfulPaymentAudit(AuditAction.PAYMENT_REVERSAL, reversal);
    }

    @Test
    void recordsFailedRefundWhenTheCustomerWalletHasInsufficientFunds() {
        var customer = accountsApi.create("Spent Refund Customer");
        var recipient = accountsApi.create("Spent Refund Recipient");
        var original = paymentsApi.topUp(
                new TopUpCommand("spent-refund-source", customer.id(), 500));
        paymentsApi.transfer(new TransferCommand(
                "spend-refund-balance", customer.id(), recipient.id(), 500));

        var refund = paymentsApi.reverse(new ReversePaymentCommand(
                "spent-refund-1", original.id(), "Customer requested refund"));

        assertThat(refund.status()).isEqualTo("FAILED");
        assertThat(refund.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(accountsApi.balance(customer.id()).cents()).isZero();
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, refund.channelReference())).isZero();
        assertThat(auditApi.findRecent(AuditAction.PAYMENT_REFUND, null, 100))
                .singleElement()
                .extracting(AuditEventView::outcome, AuditEventView::summary)
                .containsExactly(
                        AuditOutcome.FAILED,
                        "REFUND CNY 500 FAILED INSUFFICIENT_FUNDS");
    }

    @Test
    void retriesAFailedRefundWithANewKeyAfterFunding() {
        var customer = accountsApi.create("Retry Refund Customer");
        var recipient = accountsApi.create("Retry Refund Recipient");
        var original = paymentsApi.topUp(
                new TopUpCommand("retry-refund-source", customer.id(), 500));
        paymentsApi.transfer(new TransferCommand(
                "spend-retry-refund", customer.id(), recipient.id(), 500));
        var failedCommand = new ReversePaymentCommand(
                "retry-refund-failed", original.id(), "Initial refund attempt");

        var failed = paymentsApi.reverse(failedCommand);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(count("""
                SELECT COUNT(*) FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, failed.channelReference())).isZero();

        paymentsApi.topUp(new TopUpCommand("fund-retry-refund", customer.id(), 500));
        var succeededCommand = new ReversePaymentCommand(
                "retry-refund-succeeded", original.id(), "Refund after funding");
        var succeeded = paymentsApi.reverse(succeededCommand);
        var failedReplay = paymentsApi.reverse(failedCommand);
        var succeededReplay = paymentsApi.reverse(succeededCommand);

        assertThat(succeeded.id()).isNotEqualTo(failed.id());
        assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
        assertThat(failedReplay.id()).isEqualTo(failed.id());
        assertThat(failedReplay.status()).isEqualTo("FAILED");
        assertThat(succeededReplay.id()).isEqualTo(succeeded.id());
        assertThat(succeededReplay.status()).isEqualTo("SUCCEEDED");
        assertThat(accountsApi.balance(customer.id()).cents()).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ?
                """, original.id())).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ? AND status = 'FAILED'
                """, original.id())).isOne();
        assertThat(count("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ? AND status = 'SUCCEEDED'
                """, original.id())).isOne();
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction transaction
                JOIN payments.payment_instruction payment
                  ON payment.channel_reference = transaction.business_reference
                WHERE payment.original_payment_id = ?
                """, original.id())).isOne();
        assertThat(auditApi.findRecent(
                AuditAction.PAYMENT_REFUND, AuditOutcome.FAILED, 100))
                .singleElement()
                .extracting(AuditEventView::aggregateId)
                .isEqualTo(failed.id().toString());
        assertThat(auditApi.findRecent(
                AuditAction.PAYMENT_REFUND, AuditOutcome.SUCCEEDED, 100))
                .singleElement()
                .extracting(AuditEventView::aggregateId)
                .isEqualTo(succeeded.id().toString());
    }

    @Test
    void returnsTheExistingReverseForTheSameRequestAndActiveOriginal() {
        var account = accountsApi.create("Repeated Refund Customer");
        var original = paymentsApi.topUp(
                new TopUpCommand("repeated-refund-source", account.id(), 500));
        var command = new ReversePaymentCommand(
                "repeated-refund-1", original.id(), "Customer refund");

        var first = paymentsApi.reverse(command);
        var replay = paymentsApi.reverse(command);
        var differentKey = paymentsApi.reverse(new ReversePaymentCommand(
                "repeated-refund-2", original.id(), "Another explanation"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(differentKey.id()).isEqualTo(first.id());
        assertThat(count("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ?
                """, original.id())).isOne();
        assertThat(count("""
                SELECT COUNT(*) FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, first.channelReference())).isOne();
    }

    @Test
    void processesConcurrentDifferentReverseKeysForTheSameOriginalOnce() throws Exception {
        var account = accountsApi.create("Concurrent Refund Customer");
        var original = paymentsApi.topUp(
                new TopUpCommand("concurrent-refund-source", account.id(), 500));
        var first = new ReversePaymentCommand(
                "concurrent-refund-1", original.id(), "First refund request");
        var second = new ReversePaymentCommand(
                "concurrent-refund-2", original.id(), "Second refund request");

        var attempts = reverseConcurrently(first, second);

        assertThat(attempts).extracting(Attempt::error).containsOnlyNulls();
        assertThat(attempts).extracting(attempt -> attempt.payment().id())
                .containsOnly(attempts.get(0).payment().id());
        assertThat(attempts).extracting(attempt -> attempt.payment().status())
                .containsOnly("SUCCEEDED");
        var reverse = attempts.get(0).payment();
        assertThat(accountsApi.balance(account.id()).cents()).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ?
                """, original.id())).isOne();
        assertThat(count("""
                SELECT COUNT(*) FROM payments.payment_instruction
                WHERE original_payment_id = ? AND status IN ('PENDING', 'SUCCEEDED')
                """, original.id())).isOne();
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_transaction transaction
                JOIN payments.payment_instruction payment
                  ON payment.channel_reference = transaction.business_reference
                WHERE payment.original_payment_id = ?
                """, original.id())).isOne();
        assertThat(count("""
                SELECT COUNT(*)
                FROM ledger.ledger_entry entry
                JOIN ledger.ledger_transaction transaction
                  ON transaction.id = entry.transaction_id
                WHERE transaction.business_reference = ?
                """, reverse.channelReference())).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM audit.audit_event
                WHERE action = 'PAYMENT_REFUND'
                """)).isOne();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT aggregate_id, outcome FROM audit.audit_event
                WHERE action = 'PAYMENT_REFUND'
                """))
                .containsEntry("aggregate_id", reverse.id().toString())
                .containsEntry("outcome", "SUCCEEDED");
    }

    @Test
    void rejectsAReusedReverseIdempotencyKeyWithDifferentPayload() {
        var firstAccount = accountsApi.create("First Refund Conflict Customer");
        var secondAccount = accountsApi.create("Second Refund Conflict Customer");
        var firstOriginal = paymentsApi.topUp(
                new TopUpCommand("first-refund-conflict-source", firstAccount.id(), 500));
        var secondOriginal = paymentsApi.topUp(
                new TopUpCommand("second-refund-conflict-source", secondAccount.id(), 500));
        paymentsApi.reverse(new ReversePaymentCommand(
                "refund-conflict-1", firstOriginal.id(), "First reason"));

        assertThatThrownBy(() -> paymentsApi.reverse(new ReversePaymentCommand(
                "refund-conflict-1", secondOriginal.id(), "Second reason")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(accountsApi.balance(secondAccount.id()).cents()).isEqualTo(500);
    }

    @Test
    void rejectsAReusedReverseIdempotencyKeyWhenOnlyReasonChanges() {
        var account = accountsApi.create("Refund Reason Conflict Customer");
        var original = paymentsApi.topUp(
                new TopUpCommand("refund-reason-conflict-source", account.id(), 500));
        paymentsApi.reverse(new ReversePaymentCommand(
                "refund-reason-conflict", original.id(), "First reason"));

        assertThatThrownBy(() -> paymentsApi.reverse(new ReversePaymentCommand(
                "refund-reason-conflict", original.id(), "Second reason")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectsNullReverseCommandWithoutMutation() {
        assertRejectedReverseWithoutMutation(null)
                .hasMessage("Reverse payment command is required");
    }

    @Test
    void rejectsNullReverseIdempotencyKeyWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                null, UUID.randomUUID(), "Customer refund"))
                .hasMessage("Idempotency key is required");
    }

    @Test
    void rejectsBlankReverseIdempotencyKeyWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                " \t ", UUID.randomUUID(), "Customer refund"))
                .hasMessage("Idempotency key is required");
    }

    @Test
    void rejectsReverseIdempotencyKeyLongerThan128UnicodeCodePointsWithoutMutation() {
        String supplementaryCharacter = new String(Character.toChars(0x10400));

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                supplementaryCharacter.repeat(129), UUID.randomUUID(), "Customer refund"))
                .hasMessage("Idempotency key must not exceed 128 characters");
    }

    @Test
    void rejectsNulInReverseIdempotencyKeyWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "invalid\0key", UUID.randomUUID(), "Customer refund"))
                .hasMessage("Idempotency key must not contain control characters");
    }

    @Test
    void rejectsControlCharactersInReverseIdempotencyKeyWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "invalid\u001Fkey", UUID.randomUUID(), "Customer refund"))
                .hasMessage("Idempotency key must not contain control characters");
    }

    @Test
    void rejectsNullReverseReasonWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "null-reason-refund", UUID.randomUUID(), null))
                .hasMessage("Reverse payment reason is required");
    }

    @Test
    void rejectsBlankReverseReasonWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "blank-reason-refund", UUID.randomUUID(), " \t "))
                .hasMessage("Reverse payment reason is required");
    }

    @Test
    void rejectsReverseReasonLongerThan500UnicodeCodePointsWithoutMutation() {
        String supplementaryCharacter = new String(Character.toChars(0x10400));

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "long-reason-refund", UUID.randomUUID(), supplementaryCharacter.repeat(501)))
                .hasMessage("Reverse payment reason must not exceed 500 characters");
    }

    @Test
    void rejectsControlCharactersInReverseReasonWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "control-reason-refund", UUID.randomUUID(), "invalid\u001Freason"))
                .hasMessage("Reverse payment reason must not contain control characters");
    }

    @Test
    void rejectsNulInReverseReasonWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "nul-reason-refund", UUID.randomUUID(), "invalid\0reason"))
                .hasMessage("Reverse payment reason must not contain control characters");
    }

    @Test
    void rejectsNullOriginalPaymentWithoutMutation() {
        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "null-original-refund", null, "Customer refund"))
                .hasMessage("Original payment id is required");
    }

    @Test
    void rejectsMissingOriginalPaymentWithoutMutation() {
        var missingId = UUID.randomUUID();

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "missing-original-refund", missingId, "Customer refund"))
                .hasMessage("Original payment does not exist: " + missingId);
    }

    @Test
    void rejectsPendingOriginalPaymentWithoutMutation() {
        var account = accountsApi.create("Pending Original Customer");
        var originalId = UUID.randomUUID();
        insertPaymentFixture(
                originalId, "pending-original", "TOP_UP", null, account.id(), 500,
                "PENDING", null, null, null);

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "pending-original-refund", originalId, "Customer refund"))
                .hasMessage("Original payment must be successful");
    }

    @Test
    void rejectsFailedOriginalPaymentWithoutMutation() {
        var account = accountsApi.create("Failed Original Customer");
        var originalId = UUID.randomUUID();
        insertPaymentFixture(
                originalId, "failed-original", "TOP_UP", null, account.id(), 500,
                "FAILED", "PROCESSING_REJECTED", null, null);

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "failed-original-refund", originalId, "Customer refund"))
                .hasMessage("Original payment must be successful");
    }

    @Test
    void rejectsRefundAsAnOriginalPaymentWithoutMutation() {
        var account = accountsApi.create("Reverse Refund Customer");
        var topUp = paymentsApi.topUp(
                new TopUpCommand("reverse-refund-topup", account.id(), 500));
        var refundId = UUID.randomUUID();
        insertPaymentFixture(
                refundId, "reverse-refund-source", "REFUND", null, account.id(), 500,
                "SUCCEEDED", null, topUp.id(), "Previous refund");

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "reverse-refund-attempt", refundId, "Reverse a refund"))
                .hasMessage("Original payment type cannot be reversed: REFUND");
    }

    @Test
    void rejectsReversalAsAnOriginalPaymentWithoutMutation() {
        var payer = accountsApi.create("Reverse Reversal Payer");
        var payee = accountsApi.create("Reverse Reversal Payee");
        var topUp = paymentsApi.topUp(
                new TopUpCommand("reverse-reversal-topup", payer.id(), 500));
        var transfer = paymentsApi.transfer(new TransferCommand(
                "reverse-reversal-transfer", payer.id(), payee.id(), 500));
        var reversalId = UUID.randomUUID();
        insertPaymentFixture(
                reversalId, "reverse-reversal-source", "REVERSAL", payer.id(), payee.id(),
                500, "SUCCEEDED", null, transfer.id(), "Previous reversal");

        assertRejectedReverseWithoutMutation(new ReversePaymentCommand(
                "reverse-reversal-attempt", reversalId, "Reverse a reversal"))
                .hasMessage("Original payment type cannot be reversed: REVERSAL");
        assertThat(paymentsApi.get(topUp.id()).status()).isEqualTo("SUCCEEDED");
    }

    private List<Attempt> topUpConcurrently(TopUpCommand first, TopUpCommand second)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var firstFuture = executor.submit(concurrentTopUp(first, ready, start));
            var secondFuture = executor.submit(concurrentTopUp(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Attempt> concurrentTopUp(
            TopUpCommand command, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return new Attempt(paymentsApi.topUp(command), null);
            } catch (RuntimeException exception) {
                return new Attempt(null, exception);
            }
        };
    }

    private List<Attempt> transferConcurrently(TransferCommand first, TransferCommand second)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var firstFuture = executor.submit(concurrentTransfer(first, ready, start));
            var secondFuture = executor.submit(concurrentTransfer(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Attempt> concurrentTransfer(
            TransferCommand command, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return new Attempt(paymentsApi.transfer(command), null);
            } catch (RuntimeException exception) {
                return new Attempt(null, exception);
            }
        };
    }

    private List<Attempt> reverseConcurrently(
            ReversePaymentCommand first, ReversePaymentCommand second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var firstFuture = executor.submit(concurrentReverse(first, ready, start));
            var secondFuture = executor.submit(concurrentReverse(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Attempt> concurrentReverse(
            ReversePaymentCommand command, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return new Attempt(paymentsApi.reverse(command), null);
            } catch (RuntimeException exception) {
                return new Attempt(null, exception);
            }
        };
    }

    private long count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertRejectedReverseWithoutMutation(ReversePaymentCommand command) {
        long paymentCount = count("SELECT COUNT(*) FROM payments.payment_instruction");
        long journalCount = count("SELECT COUNT(*) FROM ledger.ledger_transaction");
        long reverseAuditCount = count("""
                SELECT COUNT(*) FROM audit.audit_event
                WHERE action IN ('PAYMENT_REFUND', 'PAYMENT_REVERSAL')
                """);

        var assertion = assertThatThrownBy(() -> paymentsApi.reverse(command))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(count("SELECT COUNT(*) FROM payments.payment_instruction"))
                .isEqualTo(paymentCount);
        assertThat(count("SELECT COUNT(*) FROM ledger.ledger_transaction"))
                .isEqualTo(journalCount);
        assertThat(count("""
                SELECT COUNT(*) FROM audit.audit_event
                WHERE action IN ('PAYMENT_REFUND', 'PAYMENT_REVERSAL')
                """)).isEqualTo(reverseAuditCount);
        return assertion;
    }

    private void assertSuccessfulPaymentAudit(AuditAction action, PaymentView payment) {
        assertThat(auditApi.findRecent(action, null, 100))
                .singleElement()
                .extracting(
                        AuditEventView::aggregateType,
                        AuditEventView::aggregateId,
                        AuditEventView::outcome,
                        AuditEventView::summary,
                        AuditEventView::correlationReference)
                .containsExactly(
                        "PAYMENT",
                        payment.id().toString(),
                        AuditOutcome.SUCCEEDED,
                        payment.type() + " CNY " + payment.amountCents() + " SUCCEEDED",
                        payment.channelReference());
    }

    private UUID platformCashAccountId() {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM ledger.ledger_account WHERE owner_ref = 'PLATFORM_CASH'
                """, UUID.class);
    }

    private String journalType(String businessReference) {
        return jdbcTemplate.queryForObject("""
                SELECT transaction_type FROM ledger.ledger_transaction
                WHERE business_reference = ?
                """, String.class, businessReference);
    }

    private List<JournalEntrySnapshot> journalSnapshot(String businessReference) {
        return jdbcTemplate.query("""
                SELECT entry.id, entry.ledger_account_id, entry.side, entry.amount_cents
                FROM ledger.ledger_entry entry
                JOIN ledger.ledger_transaction transaction ON transaction.id = entry.transaction_id
                WHERE transaction.business_reference = ?
                ORDER BY entry.id
                """, (resultSet, rowNumber) -> new JournalEntrySnapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("ledger_account_id", UUID.class),
                        resultSet.getString("side"),
                        resultSet.getLong("amount_cents")), businessReference);
    }

    private List<JournalEntrySnapshot> journalEntries(String businessReference) {
        return journalSnapshot(businessReference);
    }

    private void insertReverseFixture(
            UUID id,
            String idempotencyKey,
            UUID originalPaymentId,
            UUID payeeAccountId,
            String status,
            String failureReason) {
        var now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO payments.payment_instruction
                    (id, idempotency_key, request_fingerprint, channel_reference, payment_type,
                     payer_account_id, payee_account_id, amount_cents, currency, status,
                     failure_reason, version, created_at, completed_at,
                     original_payment_id, operation_reason)
                VALUES (?, ?, ?, ?, 'REFUND', NULL, ?, 500, 'CNY', ?, ?, 0, ?, ?, ?, ?)
                """,
                id,
                idempotencyKey,
                "f".repeat(64),
                "REFUND-" + id,
                payeeAccountId,
                status,
                failureReason,
                now,
                "FAILED".equals(status) ? now : null,
                originalPaymentId,
                "Customer refund");
    }

    private void insertPaymentFixture(
            UUID id,
            String idempotencyKey,
            String paymentType,
            UUID payerAccountId,
            UUID payeeAccountId,
            long amountCents,
            String status,
            String failureReason,
            UUID originalPaymentId,
            String operationReason) {
        var now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO payments.payment_instruction
                    (id, idempotency_key, request_fingerprint, channel_reference, payment_type,
                     payer_account_id, payee_account_id, amount_cents, currency, status,
                     failure_reason, version, created_at, completed_at,
                     original_payment_id, operation_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CNY', ?, ?, 0, ?, ?, ?, ?)
                """,
                id,
                idempotencyKey,
                "e".repeat(64),
                paymentType.replace("_", "") + "-" + id,
                paymentType,
                payerAccountId,
                payeeAccountId,
                amountCents,
                status,
                failureReason,
                now,
                "PENDING".equals(status) ? null : now,
                originalPaymentId,
                operationReason);
    }

    private record Attempt(PaymentView payment, RuntimeException error) {}

    private record JournalEntrySnapshot(UUID id, UUID accountId, String side, long amountCents) {}
}
