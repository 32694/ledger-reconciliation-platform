package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {
    private final ApplicationModules modules =
            ApplicationModules.of(LedgerReconciliationApplication.class);

    @Test
    void exposesOnlyApprovedModules() {
        Set<String> names = modules.stream()
                .map(module -> module.getName())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "identity", "accounts", "ledger", "payments", "reconciliation", "audit");
    }

    @Test
    void verifiesModuleDependencies() {
        modules.verify();
    }

    @Test
    void closesModulesWithoutDependencies() throws ClassNotFoundException {
        Package identity = Class.forName("io.github.user32694.ledgerplatform.identity.package-info")
                .getPackage();
        Package ledger = Class.forName("io.github.user32694.ledgerplatform.ledger.package-info")
                .getPackage();
        Package audit = Class.forName("io.github.user32694.ledgerplatform.audit.package-info")
                .getPackage();

        assertThat(identity.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
        assertThat(ledger.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
        assertThat(audit.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
    }

    @Test
    void businessModulesDeclareOnlyRequiredDependencies() throws ClassNotFoundException {
        Package accounts = Class.forName(
                        "io.github.user32694.ledgerplatform.accounts.package-info")
                .getPackage();
        Package payments = Class.forName(
                        "io.github.user32694.ledgerplatform.payments.package-info")
                .getPackage();
        Package reconciliation = Class.forName(
                        "io.github.user32694.ledgerplatform.reconciliation.package-info")
                .getPackage();

        assertThat(accounts.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("ledger", "audit");
        assertThat(payments.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("accounts", "ledger", "audit");
        assertThat(reconciliation.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("payments", "audit");
    }
}
