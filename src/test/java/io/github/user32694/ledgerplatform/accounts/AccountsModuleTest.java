package io.github.user32694.ledgerplatform.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.test.context.jdbc.SqlMergeMode.MergeMode;

@ApplicationModuleTest(extraIncludes = "ledger")
@ActiveProfiles("test")
@SqlMergeMode(MergeMode.MERGE)
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
    @Autowired JdbcTemplate jdbcTemplate;

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
    void acceptsTwoCharacterOwnerNameAfterStripping() {
        var account = accountsApi.create("  AB  ");

        assertThat(account.ownerName()).isEqualTo("AB");
    }

    @Test
    void rejectsUnicodeWhitespaceOnlyOwnerName() {
        assertThatThrownBy(() -> accountsApi.create("\u2003\u2003"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner name");
    }

    @Test
    void countsOwnerNameLengthInUnicodeCodePoints() {
        String supplementaryCharacter = new String(Character.toChars(0x10400));
        String twoCodePoints = supplementaryCharacter.repeat(2);
        String oneHundredCodePoints = supplementaryCharacter.repeat(100);

        assertThat(accountsApi.create(twoCodePoints).ownerName()).isEqualTo(twoCodePoints);
        assertThat(accountsApi.create(oneHundredCodePoints).ownerName())
                .isEqualTo(oneHundredCodePoints);
        assertThatThrownBy(() -> accountsApi.create(supplementaryCharacter.repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    @Sql(statements = {
        "ALTER TABLE accounts.customer_account DROP CONSTRAINT IF EXISTS ck_test_account_insert_failure",
        "ALTER TABLE accounts.customer_account ADD CONSTRAINT ck_test_account_insert_failure CHECK (FALSE)"
    }, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
    @Sql(statements = {
        "ALTER TABLE accounts.customer_account DROP CONSTRAINT IF EXISTS ck_test_account_insert_failure"
    }, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
    void rollsBackWalletWhenCustomerAccountPersistenceFails() {
        assertThatThrownBy(() -> accountsApi.create("Rollback Customer"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long walletCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger.ledger_account", Long.class);
        assertThat(walletCount).isZero();
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
