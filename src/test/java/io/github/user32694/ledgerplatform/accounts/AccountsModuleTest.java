package io.github.user32694.ledgerplatform.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

@ApplicationModuleTest(extraIncludes = "ledger")
@ActiveProfiles("test")
@Sql(statements = {
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
    "DELETE FROM accounts.customer_account",
    "DELETE FROM ledger.ledger_entry",
    "DELETE FROM ledger.ledger_transaction",
    "DELETE FROM ledger.ledger_account"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AccountsModuleTest {
    @Autowired AccountsApi accountsApi;

    @Test
    void createsActiveCnyAccountWithZeroBalance() {
        var account = accountsApi.create("Test Customer");

        assertThat(account.accountNumber()).startsWith("ACC-");
        assertThat(account.ownerName()).isEqualTo("Test Customer");
        assertThat(account.status()).isEqualTo("ACTIVE");
        assertThat(account.accountNumber()).hasSizeLessThanOrEqualTo(32);
        assertThat(accountsApi.balance(account.id()))
                .isEqualTo(new AccountBalance(0, "CNY"));
    }

    @Test
    void trimsAndFindsCreatedAccounts() {
        var first = accountsApi.create("  First Customer  ");
        var second = accountsApi.create("Second Customer");

        assertThat(accountsApi.get(first.id())).isEqualTo(first);
        assertThat(first.ownerName()).isEqualTo("First Customer");
        assertThat(accountsApi.findAll()).containsExactlyInAnyOrder(first, second);
        assertThat(accountsApi.findAll())
                .extracting(CustomerAccountView::accountNumber)
                .isSorted();
    }

    @Test
    void rejectsInvalidOwnerNames() {
        assertThatThrownBy(() -> accountsApi.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner name");
        assertThatThrownBy(() -> accountsApi.create(" x "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
        assertThatThrownBy(() -> accountsApi.create("x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void rejectsUnknownAccountIds() {
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> accountsApi.get(accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(accountId.toString());
        assertThatThrownBy(() -> accountsApi.balance(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account id");
    }
}
