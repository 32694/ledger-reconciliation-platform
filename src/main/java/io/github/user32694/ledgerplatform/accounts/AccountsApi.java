package io.github.user32694.ledgerplatform.accounts;

import java.util.List;
import java.util.UUID;

public interface AccountsApi {
    CustomerAccountView create(String ownerName);
    CustomerAccountView get(UUID accountId);
    List<CustomerAccountView> findAll();
    AccountBalance balance(UUID accountId);
}
