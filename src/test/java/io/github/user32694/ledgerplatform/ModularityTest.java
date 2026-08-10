package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.user32694.ledgerplatform.accounts.AccountsApi;
import io.github.user32694.ledgerplatform.payments.PaymentsApi;
import io.github.user32694.ledgerplatform.reconciliation.ReconciliationApi;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
                "identity", "accounts", "ledger", "payments", "reconciliation", "audit",
                "messaging", "notifications");
    }

    @Test
    void verifiesModuleDependencies() {
        modules.verify();
    }

    @Test
    void overviewUsesOnlyPublicModuleApis() {
        assertThat(OverviewController.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(AccountsApi.class, PaymentsApi.class, ReconciliationApi.class));
        assertThat(Arrays.stream(OverviewController.class.getDeclaredFields())
                        .map(field -> field.getType().getName()))
                .noneMatch(name -> name.contains(".internal.") || name.endsWith("Repository"));
        assertThat(OverviewController.class.getAnnotation(ConditionalOnBean.class).value())
                .containsExactly(AccountsApi.class, PaymentsApi.class, ReconciliationApi.class);
    }

    @Test
    void closesModulesWithoutDependencies() throws ClassNotFoundException {
        Package identity = Class.forName("io.github.user32694.ledgerplatform.identity.package-info")
                .getPackage();
        Package ledger = Class.forName("io.github.user32694.ledgerplatform.ledger.package-info")
                .getPackage();
        Package audit = Class.forName("io.github.user32694.ledgerplatform.audit.package-info")
                .getPackage();
        Package messaging = Class.forName("io.github.user32694.ledgerplatform.messaging.package-info")
                .getPackage();

        assertThat(identity.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
        assertThat(ledger.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
        assertThat(audit.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
        assertThat(messaging.getAnnotation(ApplicationModule.class).allowedDependencies()).isEmpty();
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
        Package notifications = Class.forName(
                        "io.github.user32694.ledgerplatform.notifications.package-info")
                .getPackage();

        assertThat(accounts.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("ledger", "audit");
        assertThat(payments.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("accounts", "ledger", "audit");
        assertThat(reconciliation.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("payments", "audit");
        assertThat(notifications.getAnnotation(ApplicationModule.class).allowedDependencies())
                .containsExactly("messaging");
    }
}
