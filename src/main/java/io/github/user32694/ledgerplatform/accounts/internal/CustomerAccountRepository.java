package io.github.user32694.ledgerplatform.accounts.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerAccountRepository extends JpaRepository<CustomerAccountEntity, UUID> {
    List<CustomerAccountEntity> findAllByOrderByAccountNumberAsc();
}
