package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {
    private final ApplicationModules modules =
            ApplicationModules.of(LedgerReconciliationApplication.class);

    @Test
    void exposesOnlyApprovedModules() {
        Set<String> names = modules.stream()
                .map(module -> module.getName())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("identity", "accounts", "ledger", "payments");
    }

    @Test
    void verifiesModuleDependencies() {
        modules.verify();
    }
}
