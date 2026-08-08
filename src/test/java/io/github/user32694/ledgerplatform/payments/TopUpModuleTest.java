package io.github.user32694.ledgerplatform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.user32694.ledgerplatform.audit.AuditAction;
import io.github.user32694.ledgerplatform.audit.AuditApi;
import io.github.user32694.ledgerplatform.audit.AuditEventView;
import io.github.user32694.ledgerplatform.audit.AuditOutcome;
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

    private long count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    private void insertReverseFixture(
            UUID id,
            String idempotencyKey,
            UUID originalPaymentId,
            UUID payeeAccountId,
            String status,
            String failureReason) {
        var now = Instant.now();
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

    private record Attempt(PaymentView payment, RuntimeException error) {}
}
