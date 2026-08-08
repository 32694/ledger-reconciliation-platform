package io.github.user32694.ledgerplatform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@ApplicationModuleTest(extraIncludes = {"accounts", "ledger"})
@ActiveProfiles("test")
@SqlMergeMode(MergeMode.MERGE)
@Sql(statements = {
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
    "DELETE FROM payments.payment_instruction",
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TopUpModuleTest {
    @Autowired PaymentsApi paymentsApi;
    @Autowired io.github.user32694.ledgerplatform.accounts.AccountsApi accountsApi;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void postsTopUpOnceForRepeatedRequest() {
        var account = accountsApi.create("Top Up Customer");
        var command = new TopUpCommand("idem-topup-1", account.id(), 5000);

        var first = paymentsApi.topUp(command);
        var repeated = paymentsApi.topUp(command);

        assertThat(first.id()).isEqualTo(repeated.id());
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(accountsApi.balance(account.id()).cents()).isEqualTo(5000);
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

    private long count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    private record Attempt(PaymentView payment, RuntimeException error) {}
}
