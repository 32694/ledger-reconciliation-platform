# Refund, Reversal, and Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add full top-up refunds, full transfer reversals, linked immutable journals, and a Chinese append-only business audit interface.

**Architecture:** Keep reverse payments inside the existing `payments` module and reuse its idempotent instruction plus immutable ledger-posting flow. Add a dependency-free `audit` module with a public insert/query API; `accounts`, `payments`, and `reconciliation` record events inside their state-changing transactions. PostgreSQL constraints enforce valid reverse instruction shapes and at most one active or successful reverse per original payment.

**Tech Stack:** JDK 17, Spring Boot 3.5, Spring Modulith, Spring Data JPA, PostgreSQL 17, Flyway, Thymeleaf, HTMX, Spring Security, JUnit 5, AssertJ, MockMvc, ArchUnit, Maven Wrapper.

---

### Task 1: Add forward-only schemas and the append-only audit module

**Files:**
- Create: `src/main/resources/db/migration/V9__add_payment_refunds_and_reversals.sql`
- Create: `src/main/resources/db/migration/V10__create_audit_events.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditAction.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditOutcome.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditCommand.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditEventView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/package-info.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/internal/AuditEventEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/internal/AuditEventRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/internal/AuditService.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/audit/AuditModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationImmutabilityTest.java`

- [ ] **Step 1: Write failing audit persistence and module tests**

Add tests that describe the public contract before creating it:

```java
@ApplicationModuleTest
@ActiveProfiles("test")
@Sql(statements = "DELETE FROM audit.audit_event",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuditModuleTest {
    @Autowired AuditApi auditApi;

    @Test
    void recordsAndFiltersNewestBusinessEvents() {
        auditApi.record(new AuditCommand(
                "operator-1", AuditAction.PAYMENT_TOP_UP, "PAYMENT", "p-1",
                AuditOutcome.SUCCEEDED, "充值成功", "TOPUP-1"));
        auditApi.record(new AuditCommand(
                "operator-2", AuditAction.PAYMENT_TRANSFER, "PAYMENT", "p-2",
                AuditOutcome.FAILED, "转账失败", "TRANSFER-1"));

        assertThat(auditApi.findRecent(null, AuditOutcome.FAILED, 100))
                .extracting(AuditEventView::aggregateId)
                .containsExactly("p-2");
        assertThat(auditApi.findRecent(AuditAction.PAYMENT_TOP_UP, null, 100))
                .extracting(AuditEventView::actor)
                .containsExactly("operator-1");
    }

    @Test
    void usesSystemActorWhenCallerDoesNotProvideOne() {
        auditApi.record(new AuditCommand(
                null, AuditAction.ACCOUNT_CREATE, "ACCOUNT", "a-1",
                AuditOutcome.SUCCEEDED, "账户创建成功", null));

        assertThat(auditApi.findRecent(null, null, 100).get(0).actor())
                .isEqualTo("SYSTEM");
    }
}
```

Extend `ModularityTest.exposesOnlyApprovedModules()` with `"audit"`, assert
that `audit.package-info` has no allowed dependencies, and add migration tests
that expect Flyway versions `9` and `10`, the active reverse index predicate,
and the `audit_event` table. Extend `MigrationImmutabilityTest` with checksums
for both new migrations.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./mvnw -Dtest=AuditModuleTest,ModularityTest,MigrationIntegrationTest test
```

Expected: compilation fails because the `audit` public types and both new
migrations do not exist.

- [ ] **Step 3: Create V9 before V10, then add the audit public API**

Create V9 first so no developer database can apply V10 before discovering V9:

```sql
ALTER TABLE payments.payment_instruction
    ALTER COLUMN payment_type TYPE VARCHAR(24),
    ADD COLUMN original_payment_id UUID
        REFERENCES payments.payment_instruction(id),
    ADD COLUMN operation_reason VARCHAR(500);

ALTER TABLE payments.payment_instruction
    DROP CONSTRAINT payment_instruction_payment_type_check,
    DROP CONSTRAINT ck_payment_instruction_parties;

ALTER TABLE payments.payment_instruction
    ADD CONSTRAINT ck_payment_instruction_type CHECK (
        payment_type IN ('TOP_UP', 'TRANSFER', 'REFUND', 'REVERSAL')),
    ADD CONSTRAINT ck_payment_instruction_parties CHECK (
        (payment_type IN ('TOP_UP', 'REFUND') AND payer_account_id IS NULL)
        OR
        (payment_type IN ('TRANSFER', 'REVERSAL')
            AND payer_account_id IS NOT NULL
            AND payer_account_id <> payee_account_id)),
    ADD CONSTRAINT ck_payment_instruction_reverse_fields CHECK (
        (payment_type IN ('TOP_UP', 'TRANSFER')
            AND original_payment_id IS NULL AND operation_reason IS NULL)
        OR
        (payment_type IN ('REFUND', 'REVERSAL')
            AND original_payment_id IS NOT NULL
            AND original_payment_id <> id
            AND operation_reason IS NOT NULL
            AND char_length(btrim(operation_reason)) BETWEEN 1 AND 500));

CREATE UNIQUE INDEX uk_payment_instruction_active_reverse
    ON payments.payment_instruction (original_payment_id)
    WHERE original_payment_id IS NOT NULL
      AND status IN ('PENDING', 'SUCCEEDED');
```

Then create V10:

```sql
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_event (
    id UUID PRIMARY KEY,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    summary VARCHAR(500) NOT NULL,
    correlation_reference VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_event_occurred_at
    ON audit.audit_event (occurred_at DESC, id DESC);
```

Create these exact enum values:

```java
public enum AuditAction {
    ACCOUNT_CREATE,
    PAYMENT_TOP_UP,
    PAYMENT_TRANSFER,
    PAYMENT_REFUND,
    PAYMENT_REVERSAL,
    RECONCILIATION_IMPORT,
    RECONCILIATION_RUN,
    RECONCILIATION_RESOLVE
}

public enum AuditOutcome { SUCCEEDED, FAILED }
```

Define `AuditCommand` and `AuditEventView` with the fields from the test and
design, then expose:

```java
public interface AuditApi {
    AuditEventView record(AuditCommand command);

    List<AuditEventView> findRecent(
            AuditAction action, AuditOutcome outcome, int limit);
}
```

- [ ] **Step 4: Implement validation, actor fallback, insert, and filtering**

Implement `AuditService.record` as a regular `@Transactional` method so it
joins a caller transaction when one exists. Normalize an explicit actor with
`strip()`; otherwise use an authenticated principal and finally `SYSTEM`:

```java
private static String resolveActor(String requestedActor) {
    if (requestedActor != null && !requestedActor.isBlank()) {
        return requestedActor.strip();
    }
    Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();
    return authentication != null && authentication.isAuthenticated()
            ? authentication.getName()
            : "SYSTEM";
}
```

Reject blank or over-limit aggregate fields and summaries. Query with four
repository methods for `(action, outcome)`, action only, outcome only, or no
filters, always using `PageRequest.of(0, limit)` and
`OrderByOccurredAtDescIdDesc`.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=AuditModuleTest,ModularityTest,MigrationIntegrationTest test
```

Expected: all selected tests pass and Flyway reports V9 followed by V10.

- [ ] **Step 6: Commit the audit foundation**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/audit \
  src/main/resources/db/migration/V9__add_payment_refunds_and_reversals.sql \
  src/main/resources/db/migration/V10__create_audit_events.sql \
  src/test/java/io/github/user32694/ledgerplatform/audit \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java \
  src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java \
  src/test/java/io/github/user32694/ledgerplatform/MigrationImmutabilityTest.java
git commit -m "feat: add append-only business audit module"
```

### Task 2: Audit existing administrator business mutations

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/accounts/package-info.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/accounts/internal/AccountsService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/package-info.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentProcessor.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/package-info.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/accounts/AccountsModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`

- [ ] **Step 1: Add failing transaction-level audit assertions**

Add `audit` to the account and payment module test `extraIncludes`, delete
`audit.audit_event` in their before/after SQL, autowire `AuditApi`, and assert
one event per committed state transition:

```java
@Test
void auditsAccountCreationInTheBusinessTransaction() {
    var account = accountsApi.create("Audited Customer");

    assertThat(auditApi.findRecent(AuditAction.ACCOUNT_CREATE, null, 100))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.aggregateId()).isEqualTo(account.id().toString());
                assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            });
}

@Test
void auditsSuccessfulAndFailedPaymentsOnlyOnce() {
    var payer = accountsApi.create("Audit Payer");
    var payee = accountsApi.create("Audit Payee");
    var topUp = paymentsApi.topUp(new TopUpCommand("audit-fund", payer.id(), 100));
    var failed = paymentsApi.transfer(
            new TransferCommand("audit-transfer", payer.id(), payee.id(), 101));
    paymentsApi.topUp(new TopUpCommand("audit-fund", payer.id(), 100));

    assertThat(auditApi.findRecent(AuditAction.PAYMENT_TOP_UP, null, 100))
            .extracting(AuditEventView::aggregateId)
            .containsExactly(topUp.id().toString());
    assertThat(auditApi.findRecent(AuditAction.PAYMENT_TRANSFER, null, 100))
            .singleElement()
            .extracting(AuditEventView::aggregateId, AuditEventView::outcome)
            .containsExactly(failed.id().toString(), AuditOutcome.FAILED);
}
```

In `ReconciliationModuleTest`, assert `RECONCILIATION_IMPORT`,
`RECONCILIATION_RUN`, and `RECONCILIATION_RESOLVE` events after the existing
workflow. Add `DELETE FROM audit.audit_event` before deleting its aggregates.

- [ ] **Step 2: Run the mutation tests and verify RED**

Run:

```bash
./mvnw -Dtest=AccountsModuleTest,TopUpModuleTest,ReconciliationModuleTest,ModularityTest test
```

Expected: audit assertions are empty and dependency assertions reject the new
module edges.

- [ ] **Step 3: Record account and payment outcomes inside their transactions**

Add `"audit"` to the relevant `@ApplicationModule(allowedDependencies = ...)`
annotations. Inject `AuditApi` into `AccountsService` and record after saving:

```java
auditApi.record(new AuditCommand(
        null, AuditAction.ACCOUNT_CREATE, "ACCOUNT", account.id().toString(),
        AuditOutcome.SUCCEEDED, "客户账户创建成功", account.accountNumber()));
```

Inject `AuditApi` into `PaymentProcessor`. Record only when a `PENDING`
instruction transitions; replaying a completed instruction must return before
the audit call:

```java
payment.succeed(Instant.now());
auditApi.record(paymentAudit(payment, AuditOutcome.SUCCEEDED));
```

and in `fail(...)`:

```java
if ("PENDING".equals(payment.status())) {
    payment.fail(reason, Instant.now());
    auditApi.record(paymentAudit(payment, AuditOutcome.FAILED));
}
```

Map `TOP_UP` to `PAYMENT_TOP_UP` and `TRANSFER` to `PAYMENT_TRANSFER`. The
summary contains type, CNY cents, status, and failure reason only.

- [ ] **Step 4: Record reconciliation terminal state transitions**

Inject `AuditApi` into `ReconciliationStore` and record within these existing
transactional methods:

```java
persistImported(...)             -> RECONCILIATION_IMPORT / SUCCEEDED
persistImportFailure(...)        -> RECONCILIATION_IMPORT / FAILED
replaceResultsAndComplete(...)   -> RECONCILIATION_RUN / SUCCEEDED
markReconciliationFailed(...)    -> RECONCILIATION_RUN / FAILED
resolveResult(...)               -> RECONCILIATION_RESOLVE / SUCCEEDED
```

Use the supplied import/resolution operator when available; pass null for run
so the audit module resolves the authenticated principal or `SYSTEM`. Do not
audit idempotent reads of an already imported file or completed batch.

- [ ] **Step 5: Run tests and commit the integration**

Run:

```bash
./mvnw -Dtest=AccountsModuleTest,TopUpModuleTest,ReconciliationModuleTest,ModularityTest test
```

Expected: all selected tests pass with exactly one event per actual mutation.

```bash
git add src/main/java/io/github/user32694/ledgerplatform/{accounts,payments,reconciliation} \
  src/test/java/io/github/user32694/ledgerplatform/{accounts,payments,reconciliation} \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java
git commit -m "feat: audit administrator business mutations"
```

### Task 3: Map the reverse-payment schema and define its public contract

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/ReversePaymentCommand.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentsApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentView.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionEntity.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionRepository.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`

- [ ] **Step 1: Write failing migration and API contract tests**

Add this contract test:

```java
@Test
void exposesLinkedReversePaymentFields() {
    var originalId = UUID.randomUUID();
    var view = new PaymentView(
            UUID.randomUUID(), "REFUND-1", "REFUND", null, UUID.randomUUID(),
            500, "PENDING", null, originalId, "客户退款", null);

    assertThat(view.originalPaymentId()).isEqualTo(originalId);
    assertThat(view.operationReason()).isEqualTo("客户退款");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./mvnw -Dtest=TopUpModuleTest test
```

Expected: compilation fails for the extended `PaymentView` and missing reverse
public methods.

- [ ] **Step 3: Add public command, view compatibility, and persistence fields**

Create:

```java
public record ReversePaymentCommand(
        String idempotencyKey, UUID originalPaymentId, String reason) {}
```

Add `PaymentView.originalPaymentId` and `operationReason` before
`occurredAt`. Preserve both current constructors by delegating their missing
fields to null. Add to `PaymentsApi`:

```java
PaymentView reverse(ReversePaymentCommand command);
PaymentView get(UUID paymentId);
Optional<PaymentView> findActiveReverse(UUID originalPaymentId);
```

Map the two new entity columns and change `paymentType` length to 24. Extend
the native insert parameters with `originalPaymentId` and `operationReason`,
and use generic `ON CONFLICT DO NOTHING` so both idempotency and active-reverse
conflicts can be resolved by application reads.

- [ ] **Step 4: Run tests and commit the contract**

Run:

```bash
./mvnw -Dtest=TopUpModuleTest test
```

Expected: selected tests pass and the old constructors still compile.

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments \
  src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java
git commit -m "feat: define refund and reversal contract"
```

### Task 4: Implement full refunds and transfer reversals

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentSubmissionService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentProcessor.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentsFacade.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`

- [ ] **Step 1: Write failing happy-path and validation tests**

Add tests with explicit balance and journal assertions:

```java
@Test
void refundsASuccessfulTopUpWithAnInverseJournal() {
    var account = accountsApi.create("Refund Customer");
    var original = paymentsApi.topUp(
            new TopUpCommand("refund-source", account.id(), 5000));

    var refund = paymentsApi.reverse(new ReversePaymentCommand(
            "refund-command", original.id(), "客户申请退款"));

    assertThat(refund.type()).isEqualTo("REFUND");
    assertThat(refund.originalPaymentId()).isEqualTo(original.id());
    assertThat(refund.amountCents()).isEqualTo(5000);
    assertThat(refund.status()).isEqualTo("SUCCEEDED");
    assertThat(accountsApi.balance(account.id()).cents()).isZero();
    assertThat(count("""
            SELECT COUNT(*) FROM ledger.ledger_entry entry
            JOIN ledger.ledger_transaction tx ON tx.id = entry.transaction_id
            WHERE tx.business_reference = ?
            """, refund.channelReference())).isEqualTo(2);
}

@Test
void reversesATransferBackToTheOriginalPayer() {
    var payer = accountsApi.create("Reversal Payer");
    var payee = accountsApi.create("Reversal Payee");
    paymentsApi.topUp(new TopUpCommand("reversal-fund", payer.id(), 5000));
    var original = paymentsApi.transfer(new TransferCommand(
            "reversal-source", payer.id(), payee.id(), 1200));

    var reversal = paymentsApi.reverse(new ReversePaymentCommand(
            "reversal-command", original.id(), "原转账录入错误"));

    assertThat(reversal.type()).isEqualTo("REVERSAL");
    assertThat(accountsApi.balance(payer.id()).cents()).isEqualTo(5000);
    assertThat(accountsApi.balance(payee.id()).cents()).isZero();
}
```

Also assert that null commands, blank/501-code-point reasons, missing originals,
failed originals, and attempts to reverse `REFUND` or `REVERSAL` are rejected
without inserting another payment instruction.

- [ ] **Step 2: Run the new tests and verify RED**

Run:

```bash
./mvnw -Dtest=TopUpModuleTest test
```

Expected: tests fail because `PaymentsFacade.reverse` and reverse posting are
not implemented.

- [ ] **Step 3: Implement reverse submission**

Add `PaymentSubmissionService.submit(ReversePaymentCommand)` that:

```java
validate(command);
repository.acquireIdempotencyLock(command.idempotencyKey());
var replay = repository.findByIdempotencyKey(command.idempotencyKey());
if (replay.isPresent()) {
    return resolve(replay.orElseThrow(), reverseFingerprint(command, replay.get()));
}
var original = repository.findByIdForUpdate(command.originalPaymentId())
        .orElseThrow(() -> new IllegalArgumentException(
                "Original payment does not exist: " + command.originalPaymentId()));
validateReversible(original);
var active = repository.findActiveReverse(original.id());
if (active.isPresent()) {
    return active.orElseThrow().id();
}
```

Derive `REFUND` from `TOP_UP` and `REVERSAL` from `TRANSFER`, copy amount and
parties, strip the reason, and insert. If `insertPending(...)` returns zero,
resolve by idempotency key first and active reverse second; otherwise throw a
stable `IllegalStateException`.

- [ ] **Step 4: Post exact inverse journals and expose queries**

Extend `PaymentProcessor.process` with explicit branches:

```java
case "REFUND" -> ledgerApi.post(Journal.create(
        payment.channelReference(), "REFUND", List.of(
                new JournalEntry(customerWalletId, EntrySide.DEBIT, amount),
                new JournalEntry(platformCashId, EntrySide.CREDIT, amount))));
case "REVERSAL" -> ledgerApi.post(Journal.create(
        payment.channelReference(), "REVERSAL", List.of(
                new JournalEntry(originalPayeeWalletId, EntrySide.DEBIT, amount),
                new JournalEntry(originalPayerWalletId, EntrySide.CREDIT, amount))));
```

Map reverse types to `PAYMENT_REFUND` and `PAYMENT_REVERSAL` audit actions.
Implement `PaymentsFacade.reverse`, `get`, and `findActiveReverse`; update
`toView` with the linked fields.

- [ ] **Step 5: Run happy-path tests and commit**

Run:

```bash
./mvnw -Dtest=TopUpModuleTest,ModularityTest test
```

Expected: refund, reversal, validation, legacy payment, and module tests pass.

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments \
  src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java
git commit -m "feat: process full refunds and transfer reversals"
```

### Task 5: Prove idempotency, failure recovery, and concurrency

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentSubmissionService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentsFacade.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`

- [ ] **Step 1: Add failing edge-case tests**

Add same-key replay and conflict tests, then add failure recovery:

```java
@Test
void failedRefundCanBeRetriedWithANewKeyAfterFunding() {
    var account = accountsApi.create("Retry Refund Customer");
    var original = paymentsApi.topUp(new TopUpCommand("retry-source", account.id(), 500));
    var sink = accountsApi.create("Retry Sink");
    paymentsApi.transfer(new TransferCommand("spend-refund", account.id(), sink.id(), 500));

    var failed = paymentsApi.reverse(new ReversePaymentCommand(
            "refund-failed", original.id(), "第一次退款"));
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");

    paymentsApi.topUp(new TopUpCommand("refund-refundable", account.id(), 500));
    var succeeded = paymentsApi.reverse(new ReversePaymentCommand(
            "refund-retry", original.id(), "资金补足后重试"));

    assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
    assertThat(succeeded.id()).isNotEqualTo(failed.id());
}
```

Add a two-thread test using the existing latch/executor helper pattern. Submit
two different reverse keys for the same funded original and assert both calls
return the same payment ID, only one linked `PENDING`/`SUCCEEDED` row ever
exists, and only one reverse ledger transaction is committed.

- [ ] **Step 2: Run edge-case tests and verify RED**

Run:

```bash
./mvnw -Dtest=TopUpModuleTest test
```

Expected: at least the concurrent different-key test fails until conflict
resolution and active instruction reuse are complete.

- [ ] **Step 3: Complete deterministic conflict handling**

Keep validation priority deterministic: an existing idempotency key is checked
before original lookup, while a new key checks original eligibility before
active reverse reuse. When a generic `ON CONFLICT DO NOTHING` insert loses a
race, resolve with:

```java
return repository.findByIdempotencyKey(key)
        .filter(payment -> fingerprint.equals(payment.requestFingerprint()))
        .or(() -> repository.findActiveReverse(original.id()))
        .map(PaymentInstructionEntity::id)
        .orElseThrow(() -> new IllegalStateException(
                "Reverse payment instruction was not created"));
```

Do not catch ledger constraint errors in the web layer. The unique active
reverse index must prevent duplicate processing before ledger posting.

- [ ] **Step 4: Verify GREEN for concurrency and run the payment suite**

The RED run in Step 2 proves the conflict resolver was missing. After the
minimal resolver in Step 3, run:

```bash
./mvnw -Dtest=TopUpModuleTest test
```

Expected: every payment test passes; concurrent callers return one reverse
instruction and one journal.

- [ ] **Step 5: Commit reliability coverage**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments/internal \
  src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java
git commit -m "test: cover reverse payment concurrency and retries"
```

### Task 6: Add Chinese payment detail and reverse-operation pages

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentDetailsWebController.java`
- Create: `src/main/resources/templates/admin/payment-detail.html`
- Create: `src/main/resources/templates/admin/payment-reverse-form.html`
- Create: `src/main/resources/templates/error/404.html`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java`
- Modify: `src/main/resources/templates/admin/payment-history.html`
- Modify: `src/main/resources/templates/admin/overview.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Write failing MockMvc workflow tests**

Cover authentication, CSRF, 404, eligibility, Chinese validation, success, and
failure. The central success test is:

```java
@Test
@WithMockUser(username = "refund-admin", roles = "ADMIN")
void refundsTopUpFromItsChineseDetailPage() throws Exception {
    var account = accountsApi.create("页面退款客户");
    var original = paymentsApi.topUp(new TopUpCommand(
            "web-refund-source", account.id(), 500));

    mockMvc.perform(post("/admin/payments/{id}/reverse", original.id())
                    .with(csrf())
                    .param("reason", "客户申请全额退款")
                    .param("idempotencyKey", "web-refund-command"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/admin/payments/*"));

    mockMvc.perform(get("/admin/payments/{id}", original.id()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("已完成全额退款")))
            .andExpect(content().string(containsString("客户申请全额退款")));
}
```

Assert a failed reverse returns the form with
`可退回余额不足，请补足资金后使用新幂等键重试`, blank reason shows
`请输入退款或冲正原因`, and an unknown UUID returns HTTP 404.

- [ ] **Step 2: Run web tests and verify RED**

Run:

```bash
./mvnw -Dtest=AdminWebTest test
```

Expected: 404 for the new routes and missing Chinese content.

- [ ] **Step 3: Implement the focused detail controller**

Keep the existing funding controller unchanged except for recent-row links.
Create a separate controller with these routes and form:

```java
@GetMapping("/{paymentId}")
String detail(@PathVariable UUID paymentId, Model model)

@GetMapping("/{paymentId}/reverse")
String reverseForm(@PathVariable UUID paymentId, Model model)

@PostMapping("/{paymentId}/reverse")
String reverse(
        @PathVariable UUID paymentId,
        @Valid @ModelAttribute("reverseForm") ReverseForm form,
        BindingResult bindingResult,
        Model model)

public static class ReverseForm {
    @NotBlank(message = "请输入退款或冲正原因")
    @Size(max = 500, message = "原因不能超过500个字符")
    private String reason;

    @NotBlank(message = "请输入幂等键")
    @Size(max = 128, message = "幂等键不能超过128个字符")
    private String idempotencyKey;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
```

Use `ResponseStatusException(HttpStatus.NOT_FOUND, "交易不存在")` only for an
unknown payment. Add `templates/error/404.html` with the visible heading
`交易不存在` and a link back to `资金操作`. Redirect success to the returned
reverse payment detail. Map idempotency conflict and business failures to the
exact Chinese messages from the design.

- [ ] **Step 4: Build templates and stable row links**

Add `payment.id` to both recent payment row records and render the reference as
a link:

```html
<a class="text-link mono"
   th:href="@{/admin/payments/{id}(id=${payment.id})}"
   th:text="${payment.channelReference}">PAY-001</a>
```

The detail page uses an unframed definition grid for immutable facts and a
single command button only when eligible. The reverse form shows the original
type, amount, accounts, and readonly business reference above the two editable
fields. Add only the CSS needed for the definition grid and mobile stacking;
reuse existing buttons, alerts, fields, and status styles.

- [ ] **Step 5: Run web and payment tests, then commit**

Run:

```bash
./mvnw -Dtest=AdminWebTest,TopUpModuleTest test
```

Expected: all selected tests pass with Chinese visible text and English route,
form, and status identifiers.

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments/web \
  src/main/resources/templates/admin/{payment-detail.html,payment-reverse-form.html,payment-history.html,overview.html} \
  src/main/resources/templates/error/404.html \
  src/main/resources/static/css/admin.css \
  src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java
git commit -m "feat: add Chinese refund and reversal pages"
```

### Task 7: Add the Chinese audit log page

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/audit/web/AuditWebController.java`
- Create: `src/main/resources/templates/admin/audit-list.html`
- Modify: `src/main/resources/templates/admin/layout.html`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/OverviewController.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Write failing audit page and label tests**

Add tests that create account and payment events, then verify:

```java
@Test
@WithMockUser(roles = "ADMIN")
void showsAndFiltersChineseAuditEvents() throws Exception {
    var account = accountsApi.create("审计页面客户");
    paymentsApi.topUp(new TopUpCommand("audit-page-topup", account.id(), 100));

    mockMvc.perform(get("/admin/audit")
                    .queryParam("action", "PAYMENT_TOP_UP")
                    .queryParam("outcome", "SUCCEEDED"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin/audit-list"))
            .andExpect(content().string(containsString("业务审计日志")))
            .andExpect(content().string(containsString("账户充值")))
            .andExpect(content().string(containsString("成功")))
            .andExpect(content().string(not(containsString("ACCOUNT_CREATE"))));
}
```

Also assert anonymous access redirects to login, invalid enum filters return
HTTP 400 without a stack trace, and the overview/recent tables label `REFUND`
as `充值退款` and `REVERSAL` as `转账冲正`.

- [ ] **Step 2: Run the web tests and verify RED**

Run:

```bash
./mvnw -Dtest=AdminWebTest test
```

Expected: `/admin/audit` is missing and reverse type labels render raw English.

- [ ] **Step 3: Implement the controller and template**

Expose one read-only route:

```java
@GetMapping("/admin/audit")
String list(
        @RequestParam(required = false) AuditAction action,
        @RequestParam(required = false) AuditOutcome outcome,
        Model model) {
    model.addAttribute("events", auditApi.findRecent(action, outcome, 100));
    model.addAttribute("selectedAction", action);
    model.addAttribute("selectedOutcome", outcome);
    model.addAttribute("actions", AuditAction.values());
    model.addAttribute("outcomes", AuditOutcome.values());
    model.addAttribute("activeNav", "audit");
    return "admin/audit-list";
}
```

Render a compact filter form and table with time, actor, Chinese action,
business object, result, summary, and correlation reference. Add label methods
with exhaustive switches and raw-value fallback only where the input is a
String from a payment view.

- [ ] **Step 4: Enable navigation and reverse type labels**

Replace the disabled audit entry in `layout.html` with an active link to
`/admin/audit`. Add these mappings in both overview and payment history:

```java
case "REFUND" -> "充值退款";
case "REVERSAL" -> "转账冲正";
```

Update the overview description from “充值和转账” to “充值、转账及反向操作”.

- [ ] **Step 5: Run web tests and commit**

Run:

```bash
./mvnw -Dtest=AdminWebTest,AuditModuleTest test
```

Expected: audit list, filters, navigation, authentication, and Chinese labels
all pass.

```bash
git add src/main/java/io/github/user32694/ledgerplatform/audit/web \
  src/main/java/io/github/user32694/ledgerplatform/OverviewController.java \
  src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java \
  src/main/resources/templates/admin/{audit-list.html,layout.html,overview.html} \
  src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java
git commit -m "feat: add Chinese business audit page"
```

### Task 8: Document, migrate, and verify the complete milestone

**Files:**
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `docs/MIGRATION.md`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

- [ ] **Step 1: Add failing documentation assertions**

Extend `DocumentationTest` to require these exact concepts in the guides:

```java
assertThat(userGuide)
        .contains("全额退款")
        .contains("全额冲正")
        .contains("INSUFFICIENT_FUNDS")
        .contains("审计日志")
        .contains("使用新幂等键重试");
assertThat(migrationGuide)
        .contains("V9__add_payment_refunds_and_reversals.sql")
        .contains("V10__create_audit_events.sql")
        .contains("./mvnw clean verify");
```

- [ ] **Step 2: Run documentation tests and verify RED**

Run:

```bash
./mvnw -Dtest=DocumentationTest test
```

Expected: assertions fail because the new workflow is not documented.

- [ ] **Step 3: Update operating and migration documentation**

Document these exact operator steps:

1. Open a successful top-up or transfer from the recent payment table.
2. Submit a full refund or reversal with a reason and unique idempotency key.
3. Inspect the original and reverse payment links plus both immutable journals.
4. Open **审计日志** and filter the action and outcome.
5. For insufficient funds, replenish the source wallet and retry with a new key.

State that migration runs automatically on startup, V1-V8 remain unchanged,
PostgreSQL data is portable through the existing backup/restore instructions,
and no new external service is required.

- [ ] **Step 4: Run complete automated verification**

Run:

```bash
./mvnw clean verify
git diff --check
git status --short
```

Expected: Maven exits 0 with zero failures, `git diff --check` prints nothing,
and status lists only the intended documentation changes before the final
commit.

- [ ] **Step 5: Run browser acceptance**

Start the application with the documented PostgreSQL environment and verify at
desktop `1440x900` and mobile `390x844`:

1. Refund a funded top-up and inspect the linked detail and ledger rows.
2. Reverse a transfer and inspect restored balances.
3. Spend refundable funds, observe the Chinese insufficient-funds message,
   fund the source wallet, and retry with a new key.
4. Filter the audit page for successful and failed reverse actions.
5. Confirm no document-level horizontal overflow, overlapping controls, blank
   content, or browser console errors.

- [ ] **Step 6: Commit documentation**

```bash
git add README.md docs/USER_GUIDE.md docs/MIGRATION.md \
  src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java
git commit -m "docs: explain refund reversal and audit workflows"
```

- [ ] **Step 7: Final branch verification**

Run fresh after the commit:

```bash
./mvnw clean verify
git diff --check origin/main...HEAD
git status --short --branch
```

Expected: `clean verify` exits 0, the diff check prints nothing, and the feature
branch is clean with only intended commits ahead of `origin/main`.
