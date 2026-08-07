package io.github.user32694.ledgerplatform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    void rejectsIdempotencyKeyWithDifferentPayload() {
        var account = accountsApi.create("Conflict Customer");
        paymentsApi.topUp(new TopUpCommand("idem-conflict-1", account.id(), 5000));

        assertThatThrownBy(() -> paymentsApi.topUp(
                new TopUpCommand("idem-conflict-1", account.id(), 6000)))
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
        assertThat(failed.failureReason()).isNotBlank().hasSizeLessThanOrEqualTo(64);
        assertThat(accountsApi.balance(account.id()).cents()).isEqualTo(Long.MAX_VALUE);
        assertThat(paymentsApi.findRecent(1)).containsExactly(failed);
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
}
