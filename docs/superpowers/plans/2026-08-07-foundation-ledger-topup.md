# Foundation, Ledger, and Top-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the first runnable milestone: PostgreSQL-backed modular Spring Boot application with administrator login, customer-account creation, balanced top-ups, ledger inspection, and a compact administration UI.

**Architecture:** Use one Spring Boot 3.5 modular monolith. `accounts` owns customer accounts, `ledger` owns immutable double-entry facts, `payments` owns idempotent top-up instructions, and `identity` owns administrator authentication. Modules communicate only through root-package APIs, and PostgreSQL 17 is the only database.

**Tech Stack:** JDK 17, Spring Boot 3.5.16, Spring Modulith 1.4.12, Spring Data JPA, Spring Security, Thymeleaf, HTMX 2.0.10, PostgreSQL 17, Flyway, JUnit 5, AssertJ, MockMvc, Maven Wrapper 3.9.11

---

## Milestone Boundaries

This plan implements the foundation and top-up vertical slice. Transfer concurrency is milestone 2. CSV reconciliation and audit are milestone 3. The package structure anticipates the approved modules, but this plan creates only modules with working behavior; it does not add empty service abstractions.

## File Structure

```text
pom.xml
.mvn/wrapper/
mvnw
mvnw.cmd
.env.example
compose.yaml
src/main/java/io/github/user32694/ledgerplatform/
  LedgerReconciliationApplication.java
  config/
  identity/
  accounts/
  ledger/
  payments/
src/main/resources/
  application.yml
  db/migration/
  templates/
  static/css/
src/test/java/io/github/user32694/ledgerplatform/
docs/USER_GUIDE.md
.github/workflows/build.yml
```

### Task 1: Bootstrap a reproducible Spring Boot build

**Files:**
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `src/main/java/io/github/user32694/ledgerplatform/LedgerReconciliationApplication.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/io/github/user32694/ledgerplatform/ApplicationContextTest.java`

- [ ] **Step 1: Create build-only Maven scaffolding**

Use Spring Boot `3.5.16`, Java `17`, and import Spring Modulith BOM `1.4.12`. Include starters for web, validation, JPA, security, Thymeleaf, actuator, Flyway, PostgreSQL, Modulith core/JPA/test, and tests. Add `springdoc-openapi-starter-webmvc-ui:2.9.0` and `org.webjars.npm:htmx.org:2.0.10`.

The build must configure UTF-8, compiler release 17, and the Spring Boot Maven plugin. Do not add Redis, RabbitMQ, Lombok, MapStruct, or Testcontainers in this milestone.

Use this complete `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>
    <groupId>io.github.user32694</groupId>
    <artifactId>ledger-reconciliation-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>ledger-reconciliation-platform</name>
    <description>Simulated payment ledger and reconciliation platform</description>

    <properties>
        <java.version>17</java.version>
        <spring-modulith.version>1.4.12</spring-modulith.version>
        <springdoc.version>2.9.0</springdoc.version>
        <htmx.version>2.0.10</htmx.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.modulith</groupId>
                <artifactId>spring-modulith-bom</artifactId>
                <version>${spring-modulith.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>
        <dependency><groupId>org.thymeleaf.extras</groupId><artifactId>thymeleaf-extras-springsecurity6</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-core</artifactId></dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
        <dependency>
            <groupId>org.webjars.npm</groupId>
            <artifactId>htmx.org</artifactId>
            <version>${htmx.version}</version>
        </dependency>
        <dependency><groupId>org.webjars</groupId><artifactId>webjars-locator-lite</artifactId></dependency>

        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Generate Maven Wrapper 3.9.11**

Run:

```bash
mvn wrapper:wrapper -Dmaven=3.9.11
```

Expected: `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, and `mvnw.cmd` exist, and `./mvnw --version` reports Maven 3.9.11 with Java 17.

- [ ] **Step 3: Write the failing context test**

```java
package io.github.user32694.ledgerplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "app.admin.username=test-admin",
    "app.admin.password=test-password"
})
class ApplicationContextTest {
    @Test
    void startsApplicationContext() {}
}
```

- [ ] **Step 4: Run the test and verify RED**

Run:

```bash
./mvnw -Dtest=ApplicationContextTest test
```

Expected: compilation fails because `LedgerReconciliationApplication` does not exist.

- [ ] **Step 5: Add the minimal application class**

```java
package io.github.user32694.ledgerplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerReconciliationApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerReconciliationApplication.class, args);
    }
}
```

- [ ] **Step 6: Add base application configuration**

`application.yml` must read PostgreSQL and administrator settings from environment variables, enable Flyway, disable Hibernate schema creation, expose health/info, and reject open-session-in-view:

```yaml
spring:
  application:
    name: ledger-reconciliation-platform
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/ledger_platform}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 2MB

management:
  endpoints:
    web:
      exposure:
        include: health,info

app:
  admin:
    username: ${APP_ADMIN_USERNAME:}
    password: ${APP_ADMIN_PASSWORD:}
```

- [ ] **Step 7: Run the context test and verify GREEN**

Run `./mvnw -Dtest=ApplicationContextTest test`.

Expected: one test passes with no datasource initialization.

- [ ] **Step 8: Commit**

```bash
git add pom.xml .mvn mvnw mvnw.cmd src/main src/test
git commit -m "build: bootstrap Spring Boot application"
```

### Task 2: Enforce Spring Modulith boundaries

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/identity/package-info.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/package-info.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/package-info.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/package-info.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`

- [ ] **Step 1: Write a failing module-list test**

```java
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
```

- [ ] **Step 2: Run the test and verify RED**

Run `./mvnw -Dtest=ModularityTest test`.

Expected: `exposesOnlyApprovedModules` fails because no application modules exist.

- [ ] **Step 3: Add explicit module descriptors**

Use these dependency declarations:

```java
@org.springframework.modulith.ApplicationModule
package io.github.user32694.ledgerplatform.identity;
```

```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = "ledger")
package io.github.user32694.ledgerplatform.accounts;
```

```java
@org.springframework.modulith.ApplicationModule
package io.github.user32694.ledgerplatform.ledger;
```

```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"accounts", "ledger"})
package io.github.user32694.ledgerplatform.payments;
```

- [ ] **Step 4: Run the module tests and verify GREEN**

Run `./mvnw -Dtest=ModularityTest test`.

Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/*/package-info.java src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java
git commit -m "test: enforce application module boundaries"
```

### Task 3: Implement the double-entry ledger domain

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/Money.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/AccountType.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/EntrySide.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/JournalEntry.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/Journal.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/ledger/MoneyTest.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/ledger/JournalTest.java`

- [ ] **Step 1: Write failing Money tests**

```java
package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void createsPositiveCnyInCents() {
        assertThat(Money.cny(1200).cents()).isEqualTo(1200);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> Money.cny(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.cny(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run Money tests and verify RED**

Run `./mvnw -Dtest=MoneyTest test`.

Expected: compilation fails because `Money` does not exist.

- [ ] **Step 3: Implement Money minimally**

```java
package io.github.user32694.ledgerplatform.ledger;

public record Money(long cents, String currency) {
    public Money {
        if (cents <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("Only CNY is supported");
    }

    public static Money cny(long cents) {
        return new Money(cents, "CNY");
    }
}
```

- [ ] **Step 4: Run Money tests and verify GREEN**

Run `./mvnw -Dtest=MoneyTest test` and expect two passing tests.

- [ ] **Step 5: Write failing Journal tests**

```java
package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalTest {
    private final UUID cash = UUID.randomUUID();
    private final UUID wallet = UUID.randomUUID();

    @Test
    void acceptsBalancedEntries() {
        Journal journal = Journal.create("TOPUP-1", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(5000))));

        assertThat(journal.entries()).hasSize(2);
        assertThat(journal.totalDebits()).isEqualTo(5000);
        assertThat(journal.totalCredits()).isEqualTo(5000);
    }

    @Test
    void rejectsUnbalancedEntries() {
        assertThatThrownBy(() -> Journal.create("TOPUP-2", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet, EntrySide.CREDIT, Money.cny(4900)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void rejectsFewerThanTwoEntries() {
        assertThatThrownBy(() -> Journal.create("TOPUP-3", "TOP_UP", List.of(
                new JournalEntry(cash, EntrySide.DEBIT, Money.cny(5000)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 6: Run Journal tests and verify RED**

Run `./mvnw -Dtest=JournalTest test`.

Expected: compilation fails because the journal types do not exist.

- [ ] **Step 7: Implement the minimal journal types**

```java
package io.github.user32694.ledgerplatform.ledger;

public enum AccountType { ASSET, LIABILITY }
```

```java
package io.github.user32694.ledgerplatform.ledger;

public enum EntrySide { DEBIT, CREDIT }
```

```java
package io.github.user32694.ledgerplatform.ledger;

import java.util.Objects;
import java.util.UUID;

public record JournalEntry(UUID ledgerAccountId, EntrySide side, Money money) {
    public JournalEntry {
        Objects.requireNonNull(ledgerAccountId);
        Objects.requireNonNull(side);
        Objects.requireNonNull(money);
    }
}
```

```java
package io.github.user32694.ledgerplatform.ledger;

import java.util.List;

public record Journal(String businessReference, String type, List<JournalEntry> entries) {
    public Journal {
        if (businessReference == null || businessReference.isBlank()) {
            throw new IllegalArgumentException("Business reference is required");
        }
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("A journal requires at least two entries");
        }
        entries = List.copyOf(entries);
        long debits = entries.stream()
                .filter(entry -> entry.side() == EntrySide.DEBIT)
                .mapToLong(entry -> entry.money().cents())
                .sum();
        long credits = entries.stream()
                .filter(entry -> entry.side() == EntrySide.CREDIT)
                .mapToLong(entry -> entry.money().cents())
                .sum();
        if (debits != credits) throw new IllegalArgumentException("Journal must be balanced");
    }

    public static Journal create(String businessReference, String type, List<JournalEntry> entries) {
        return new Journal(businessReference, type, entries);
    }

    public long totalDebits() {
        return entries.stream().filter(e -> e.side() == EntrySide.DEBIT)
                .mapToLong(e -> e.money().cents()).sum();
    }

    public long totalCredits() {
        return entries.stream().filter(e -> e.side() == EntrySide.CREDIT)
                .mapToLong(e -> e.money().cents()).sum();
    }
}
```

- [ ] **Step 8: Run ledger domain tests and verify GREEN**

Run `./mvnw -Dtest=MoneyTest,JournalTest test`.

Expected: five tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/ledger src/test/java/io/github/user32694/ledgerplatform/ledger
git commit -m "feat: add balanced ledger domain"
```

### Task 4: Add PostgreSQL schema and ledger persistence

**Files:**
- Create: `src/main/resources/db/migration/V1__create_core_schemas.sql`
- Create: `src/main/resources/db/migration/V2__create_ledger_tables.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerAccountEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerTransactionEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerEntryEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerAccountRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerTransactionRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerService.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/LedgerApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/LedgerAccountView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/PostedJournal.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/ledger/LedgerPersistenceTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ApplicationContextTest.java`
- Test config: `src/test/resources/application-test.yml`

- [ ] **Step 1: Create the PostgreSQL test database**

After PostgreSQL 17 is installed and running, create local-only credentials:

```bash
psql postgres -c "CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app';"
psql postgres -c "CREATE DATABASE ledger_platform OWNER ledger_app;"
psql postgres -c "CREATE DATABASE ledger_platform_test OWNER ledger_app;"
```

Set `application-test.yml` to `jdbc:postgresql://localhost:5432/ledger_platform_test`, user `ledger_app`, password `ledger_app`, and clean the test schemas before the first run with `dropdb --if-exists ledger_platform_test && createdb -O ledger_app ledger_platform_test`.

Use this test configuration:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ledger_platform_test
    username: ledger_app
    password: ledger_app
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

app:
  admin:
    username: test-admin
    password: test-password
```

- [ ] **Step 2: Write the failing persistence test**

```java
package io.github.user32694.ledgerplatform.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LedgerPersistenceTest {
    @Autowired LedgerApi ledgerApi;

    @Test
    void postsAndReadsBalancedJournal() {
        var cash = ledgerApi.createPlatformCashAccount();
        var wallet = ledgerApi.createCustomerWallet("ACC-1001");
        var journal = Journal.create("TOPUP-1001", "TOP_UP", List.of(
                new JournalEntry(cash.id(), EntrySide.DEBIT, Money.cny(5000)),
                new JournalEntry(wallet.id(), EntrySide.CREDIT, Money.cny(5000))));

        var posted = ledgerApi.post(journal);

        assertThat(posted.businessReference()).isEqualTo("TOPUP-1001");
        assertThat(ledgerApi.walletBalance(wallet.id())).isEqualTo(5000);
    }
}
```

- [ ] **Step 3: Run the persistence test and verify RED**

Run `./mvnw -Dtest=LedgerPersistenceTest test`.

Expected: compilation fails because `LedgerApi` does not exist.

- [ ] **Step 4: Add Flyway migrations**

Use this `V1__create_core_schemas.sql`:

```sql
CREATE SCHEMA identity;
CREATE SCHEMA accounts;
CREATE SCHEMA ledger;
CREATE SCHEMA payments;
```

Use this `V2__create_ledger_tables.sql`:

```sql
CREATE TABLE ledger.ledger_account (
    id UUID PRIMARY KEY,
    owner_ref VARCHAR(64) NOT NULL UNIQUE,
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY')),
    currency CHAR(3) NOT NULL CHECK (currency = 'CNY'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger.ledger_transaction (
    id UUID PRIMARY KEY,
    business_reference VARCHAR(128) NOT NULL UNIQUE,
    transaction_type VARCHAR(32) NOT NULL CHECK (transaction_type IN ('TOP_UP', 'TRANSFER')),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger.ledger_entry (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger.ledger_transaction(id),
    ledger_account_id UUID NOT NULL REFERENCES ledger.ledger_account(id),
    side VARCHAR(8) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ledger_entry_transaction ON ledger.ledger_entry(transaction_id);
CREATE INDEX idx_ledger_entry_account ON ledger.ledger_entry(ledger_account_id);
```

- [ ] **Step 5: Implement JPA entities and repositories**

Keep JPA types under `ledger.internal`; expose no repository outside the module. Use assigned UUIDs, `Instant` timestamps, enum strings, and no setters for posted transaction fields.

- [ ] **Step 6: Implement LedgerApi**

```java
package io.github.user32694.ledgerplatform.ledger;

import java.util.UUID;

public interface LedgerApi {
    LedgerAccountView createPlatformCashAccount();
    LedgerAccountView createCustomerWallet(String customerAccountNumber);
    PostedJournal post(Journal journal);
    long walletBalance(UUID ledgerAccountId);
}
```

The implementation must return the existing platform cash account when called again, reject duplicate business references through a domain exception, save the transaction and all entries in one `@Transactional` method, and calculate liability balance as credits minus debits.

Define the public return types exactly as:

```java
package io.github.user32694.ledgerplatform.ledger;

import java.util.UUID;

public record LedgerAccountView(UUID id, String ownerReference, AccountType accountType) {}
```

```java
package io.github.user32694.ledgerplatform.ledger;

import java.util.UUID;

public record PostedJournal(UUID id, String businessReference) {}
```

- [ ] **Step 7: Convert the context test to the real PostgreSQL profile**

Remove the auto-configuration exclusions from `ApplicationContextTest`, add `@ActiveProfiles("test")`, and retain test administrator values. Run it against `ledger_platform_test`; from this task onward no test uses a database substitute.

- [ ] **Step 8: Run the persistence test and verify GREEN**

Run `./mvnw -Dtest=LedgerPersistenceTest test`.

Expected: Flyway applies both migrations and the test passes against PostgreSQL.

- [ ] **Step 9: Run domain and persistence tests together**

Run `./mvnw -Dtest=MoneyTest,JournalTest,LedgerPersistenceTest test`.

Expected: all tests pass without Hibernate schema updates.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db src/main/java/io/github/user32694/ledgerplatform/ledger src/test
git commit -m "feat: persist immutable ledger journals"
```

### Task 5: Create customer accounts atomically with wallet ledger accounts

**Files:**
- Create: `src/main/resources/db/migration/V3__create_customer_accounts.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/AccountsApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/CustomerAccountView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/AccountBalance.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/internal/CustomerAccountEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/internal/CustomerAccountRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/internal/AccountsService.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/accounts/AccountsModuleTest.java`

- [ ] **Step 1: Write a failing account-creation test**

```java
package io.github.user32694.ledgerplatform.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

@ApplicationModuleTest(extraIncludes = "ledger")
@ActiveProfiles("test")
class AccountsModuleTest {
    @Autowired AccountsApi accountsApi;

    @Test
    void createsActiveCnyAccountWithZeroBalance() {
        var account = accountsApi.create("Test Customer");

        assertThat(account.accountNumber()).startsWith("ACC-");
        assertThat(account.ownerName()).isEqualTo("Test Customer");
        assertThat(account.status()).isEqualTo("ACTIVE");
        assertThat(accountsApi.balance(account.id()).cents()).isZero();
    }
}
```

- [ ] **Step 2: Run the module test and verify RED**

Run `./mvnw -Dtest=AccountsModuleTest test`.

Expected: compilation fails because `AccountsApi` does not exist.

- [ ] **Step 3: Add the account migration**

Use this migration:

```sql
CREATE TABLE accounts.customer_account (
    id UUID PRIMARY KEY,
    account_number VARCHAR(32) NOT NULL UNIQUE,
    owner_name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    currency CHAR(3) NOT NULL CHECK (currency = 'CNY'),
    ledger_account_id UUID NOT NULL UNIQUE REFERENCES ledger.ledger_account(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 4: Implement the account module**

```java
package io.github.user32694.ledgerplatform.accounts;

import java.util.List;
import java.util.UUID;

public interface AccountsApi {
    CustomerAccountView create(String ownerName);
    CustomerAccountView get(UUID accountId);
    List<CustomerAccountView> findAll();
    AccountBalance balance(UUID accountId);
}
```

`AccountsService.create` validates a trimmed owner name of 2-100 characters, creates an account number from a UUID without punctuation, asks `LedgerApi` for the liability wallet, and saves both inside one transaction. `AccountBalance` permits zero even though transfer/top-up `Money` requires positive values.

Use these public records:

```java
package io.github.user32694.ledgerplatform.accounts;

import java.util.UUID;

public record CustomerAccountView(
        UUID id, String accountNumber, String ownerName, String status, UUID ledgerAccountId) {}
```

```java
package io.github.user32694.ledgerplatform.accounts;

public record AccountBalance(long cents, String currency) {
    public AccountBalance {
        if (cents < 0) throw new IllegalArgumentException("Balance cannot be negative");
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("Only CNY is supported");
    }
}
```

- [ ] **Step 5: Run the module test and verify GREEN**

Run `./mvnw -Dtest=AccountsModuleTest test`.

Expected: account creation and zero-balance query pass.

- [ ] **Step 6: Run architecture verification**

Run `./mvnw -Dtest=ModularityTest test`.

Expected: `accounts` depends only on the public `ledger` API.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V3__create_customer_accounts.sql src/main/java/io/github/user32694/ledgerplatform/accounts src/test/java/io/github/user32694/ledgerplatform/accounts
git commit -m "feat: create customer wallet accounts"
```

### Task 6: Add idempotent top-up processing

**Files:**
- Create: `src/main/resources/db/migration/V4__create_payment_instructions.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentsApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/TopUpCommand.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/IdempotencyConflictException.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentsFacade.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentSubmissionService.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentProcessor.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`

- [ ] **Step 1: Write failing idempotent top-up tests**

```java
package io.github.user32694.ledgerplatform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

@ApplicationModuleTest(extraIncludes = {"accounts", "ledger"})
@ActiveProfiles("test")
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
}
```

- [ ] **Step 2: Run top-up tests and verify RED**

Run `./mvnw -Dtest=TopUpModuleTest test`.

Expected: compilation fails because the payments API types do not exist.

- [ ] **Step 3: Add the payment migration**

Use this migration:

```sql
CREATE TABLE payments.payment_instruction (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    channel_reference VARCHAR(64) NOT NULL UNIQUE,
    payment_type VARCHAR(16) NOT NULL CHECK (payment_type IN ('TOP_UP', 'TRANSFER')),
    payer_account_id UUID REFERENCES accounts.customer_account(id),
    payee_account_id UUID NOT NULL REFERENCES accounts.customer_account(id),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'CNY'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_created_at ON payments.payment_instruction(created_at DESC);
```

- [ ] **Step 4: Implement top-up processing**

```java
package io.github.user32694.ledgerplatform.payments;

public interface PaymentsApi {
    PaymentView topUp(TopUpCommand command);
    java.util.List<PaymentView> findRecent(int limit);
}
```

`PaymentSubmissionService` runs with `REQUIRES_NEW`: it hashes the canonical request fields for the fingerprint, inserts `PENDING`, returns an existing payment when key and fingerprint match, and throws `IdempotencyConflictException` when they differ. `PaymentProcessor` also runs with `REQUIRES_NEW`, locks the instruction, returns terminal instructions unchanged, and posts this journal:

```java
Journal.create(payment.channelReference(), "TOP_UP", List.of(
    new JournalEntry(platformCashId, EntrySide.DEBIT, Money.cny(command.amountCents())),
    new JournalEntry(customerWalletId, EntrySide.CREDIT, Money.cny(command.amountCents()))
));
```

`PaymentsFacade` calls submission first and processing second through the two separate beans, so the pending row commits before ledger processing begins. Catch only defined business rejections inside the processing transaction to mark `FAILED`; let infrastructure exceptions roll back the processing transaction and leave the already-committed instruction `PENDING`.

Define the contracts exactly as:

```java
package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

public record TopUpCommand(String idempotencyKey, UUID payeeAccountId, long amountCents) {}
```

```java
package io.github.user32694.ledgerplatform.payments;

import java.util.UUID;

public record PaymentView(
        UUID id, String channelReference, String type, long amountCents, String status, String failureReason) {}
```

```java
package io.github.user32694.ledgerplatform.payments;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency key was already used for a different request");
    }
}
```

- [ ] **Step 5: Run top-up tests and verify GREEN**

Run `./mvnw -Dtest=TopUpModuleTest test`.

Expected: both tests pass and repeated requests create one balance change.

- [ ] **Step 6: Run all module and ledger tests**

Run:

```bash
./mvnw -Dtest=ModularityTest,MoneyTest,JournalTest,LedgerPersistenceTest,AccountsModuleTest,TopUpModuleTest test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V4__create_payment_instructions.sql src/main/java/io/github/user32694/ledgerplatform/payments src/test/java/io/github/user32694/ledgerplatform/payments
git commit -m "feat: process idempotent top ups"
```

### Task 7: Secure and render the administration UI

**Files:**
- Create: `src/main/resources/db/migration/V5__create_admin_users.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/identity/internal/AdminUserEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/identity/internal/AdminUserRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/identity/internal/AdminBootstrap.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/identity/SecurityConfig.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/OverviewController.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/accounts/web/AccountsWebController.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/web/LedgerWebController.java`
- Create: `src/main/resources/templates/login.html`
- Create: `src/main/resources/templates/admin/layout.html`
- Create: `src/main/resources/templates/admin/overview.html`
- Create: `src/main/resources/templates/admin/accounts.html`
- Create: `src/main/resources/templates/admin/account-form.html`
- Create: `src/main/resources/templates/admin/topup-form.html`
- Create: `src/main/resources/templates/admin/ledger.html`
- Create: `src/main/resources/static/css/admin.css`
- Test: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Write failing web security tests**

```java
package io.github.user32694.ledgerplatform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.admin.username=admin",
    "app.admin.password=test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminWebTest {
    @Autowired MockMvc mvc;

    @Test
    void redirectsAnonymousUserToLogin() throws Exception {
        mvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void rendersOverviewForAdministrator() throws Exception {
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/overview"));
    }

    @Test
    void rejectsPostWithoutCsrf() throws Exception {
        mvc.perform(post("/admin/accounts").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsValidAccountFormWithCsrf() throws Exception {
        mvc.perform(post("/admin/accounts")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("ownerName", "Web Customer"))
                .andExpect(status().is3xxRedirection());
    }
}
```

- [ ] **Step 2: Run web tests and verify RED**

Run `./mvnw -Dtest=AdminWebTest test`.

Expected: tests fail because security, controller, and views do not exist.

- [ ] **Step 3: Add administrator persistence and bootstrap**

Use this migration:

```sql
CREATE TABLE identity.admin_user (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);
```

At startup, require nonblank configured username/password, create the administrator when missing, and never replace an existing password automatically. Hash with Spring Security's delegating password encoder.

- [ ] **Step 4: Add SecurityConfig**

Permit `/login`, `/css/**`, `/webjars/**`, `/actuator/health`, and OpenAPI resources. Require `ADMIN` for `/admin/**`; require authentication for other endpoints. Configure custom login page, logout, CSRF, and repository-backed `UserDetailsService`.

- [ ] **Step 5: Add controllers and templates**

The overview uses the approved fixed sidebar. It shows account count, total customer balance, recent top-ups, and quick actions. Account and ledger tables are unframed, paginated-ready layouts. Forms return the same page with field-level errors; successful commands redirect to avoid duplicate browser submissions.

Use accessible labels and buttons. Use HTMX only for replacing account, top-up, and ledger table fragments; every operation must also work as a regular form submission.

- [ ] **Step 6: Run web tests and verify GREEN**

Run `./mvnw -Dtest=AdminWebTest test`.

Expected: all four tests pass.

- [ ] **Step 7: Start the application and perform a smoke test**

Run:

```bash
APP_ADMIN_USERNAME=admin APP_ADMIN_PASSWORD=change-this-now ./mvnw spring-boot:run
```

Expected: login page at `http://localhost:8080/login`; after login, creating an account and posting a top-up updates the account balance and ledger view.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V5__create_admin_users.sql src/main/java/io/github/user32694/ledgerplatform src/main/resources/templates src/main/resources/static src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java
git commit -m "feat: add secured administration console"
```

### Task 8: Add portable runtime, CI, and user documentation

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `compose.yaml`
- Create: `.github/workflows/build.yml`
- Create: `docs/USER_GUIDE.md`
- Create: `docs/MIGRATION.md`
- Create: `examples/channel-statement.csv`
- Modify: `README.md`

- [ ] **Step 1: Write a documentation verification script**

Create `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`:

```java
package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocumentationTest {
    @Test
    void shipsRequiredMigrationArtifacts() {
        assertThat(Files.isRegularFile(Path.of(".env.example"))).isTrue();
        assertThat(Files.isRegularFile(Path.of("compose.yaml"))).isTrue();
        assertThat(Files.isRegularFile(Path.of("docs/USER_GUIDE.md"))).isTrue();
        assertThat(Files.isRegularFile(Path.of("docs/MIGRATION.md"))).isTrue();
    }
}
```

- [ ] **Step 2: Run the documentation test and verify RED**

Run `./mvnw -Dtest=DocumentationTest test`.

Expected: the test fails because the runtime artifacts do not exist.

- [ ] **Step 3: Add portable configuration**

`.gitignore` must exclude `.env`, `target/`, `.idea/`, `.vscode/`, `*.iml`, `data/`, `*.dump`, `*.log`, and `.superpowers/`.

`.env.example` contains:

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/ledger_platform
DB_USERNAME=ledger_app
DB_PASSWORD=change-this-database-password
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=change-this-admin-password
```

`compose.yaml` defines PostgreSQL `17-alpine`, a named volume, health check, database/user/password environment variables, and port `5432`.

- [ ] **Step 4: Add GitHub Actions**

The workflow runs on pushes and pull requests to `main`, uses Temurin JDK 17 and Maven cache, starts PostgreSQL 17 as a service, creates `ledger_platform_test`, then runs:

```bash
./mvnw --batch-mode verify
```

Set test database and administrator values as non-secret workflow environment variables. Upload Surefire reports only on failure.

- [ ] **Step 5: Write the user and migration guides**

`USER_GUIDE.md` must include both Homebrew PostgreSQL and Docker Compose paths, environment setup, Maven commands, login, account creation, top-up, ledger inspection, health check, reset procedure, and common connection errors.

`MIGRATION.md` must include clean clone setup, `git bundle create`/`git clone` recovery, `pg_dump`/`pg_restore`, which local files are intentionally excluded, and a warning to recreate secrets on the destination machine.

README must describe the project honestly as simulated software, show the architecture modules, list prerequisites, link both guides, and cite the three reference repositories from the design.

- [ ] **Step 6: Run documentation test and verify GREEN**

Run `./mvnw -Dtest=DocumentationTest test`.

Expected: the test passes.

- [ ] **Step 7: Run the complete milestone verification**

Run:

```bash
./mvnw clean verify
```

Expected: all unit, module, PostgreSQL integration, security, web, documentation, and package tests pass; an executable JAR is created under `target/`.

- [ ] **Step 8: Check the repository for migration hazards**

Run:

```bash
git status --short
git grep -nE '/Users/|13213599216|DB_PASSWORD=ledger_app|APP_ADMIN_PASSWORD=change-this-now' -- ':!docs/superpowers/plans/*'
```

Expected: only intended source changes are present; the grep produces no output.

- [ ] **Step 9: Commit**

```bash
git add .gitignore .env.example compose.yaml .github README.md docs examples src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java
git commit -m "docs: add portable runtime and usage guides"
```

- [ ] **Step 10: Create an offline migration bundle outside the repository**

Run from the repository root:

```bash
mkdir -p ../migration-artifacts
git bundle create ../migration-artifacts/ledger-reconciliation-platform.bundle --all
git bundle verify ../migration-artifacts/ledger-reconciliation-platform.bundle
```

Expected: Git verifies the bundle and the artifact remains outside the tracked repository.

## Milestone 1 Completion Gate

Before starting the transfer/concurrency plan:

- `./mvnw clean verify` passes against PostgreSQL 17.
- The architecture test shows only `identity`, `accounts`, `ledger`, and `payments` modules.
- Manual browser smoke test proves login, account creation, top-up, balance, and ledger views.
- Repeating a top-up with the same idempotency key creates one ledger transaction.
- Git history contains focused commits matching the task boundaries.
- The repository contains no credential, database dump, absolute local path, company endpoint, or real customer data.
