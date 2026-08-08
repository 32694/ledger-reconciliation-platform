# Automatic Reconciliation Import and Matching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a complete administrator workflow that imports a synthetic-channel CSV statement, reconciles it exactly against successful top-ups, displays four deterministic result types, and records one audited manual resolution per difference.

**Architecture:** Add one `reconciliation` Spring Modulith module. Its public API accepts immutable byte-array uploads and exposes read-only batch/result views; internal services parse CSV in memory, persist imports atomically, query successful top-ups only through `PaymentsApi`, compute results in memory, and commit lifecycle transitions transactionally. PostgreSQL remains the only datastore and the existing payment and ledger facts remain immutable.

**Tech Stack:** JDK 17, Spring Boot 3.5.16, Spring Modulith 1.4.12, Spring Data JPA, Apache Commons CSV 1.14.1, Thymeleaf, HTMX 2.0.10, Spring Security, PostgreSQL 17, Flyway, JUnit 5, AssertJ, MockMvc, Maven Wrapper 3.9.11

---

## Fixed Decisions

- Source: `SYNTHETIC_CHANNEL` only.
- Header: `channel_transaction_id,amount_cents,occurred_at` exactly.
- Limits: UTF-8, 2 MB request size from `application.yml`, and at most 10,000 data rows.
- Match: channel reference first, then exact amount in cents.
- Results: `MATCHED`, `AMOUNT_MISMATCH`, `CHANNEL_ONLY`, `INTERNAL_ONLY`.
- Resolution status: matches use `NOT_REQUIRED`; differences use `OPEN` or `RESOLVED`.
- Lifecycle: `IMPORTED -> RUNNING -> COMPLETED` or `RECONCILIATION_FAILED`; invalid input becomes `IMPORT_FAILED`.
- File hash is idempotent in every lifecycle state.
- A difference can be resolved once with a required note and authenticated username.

## File Structure

```text
pom.xml
src/main/java/io/github/user32694/ledgerplatform/
  payments/
    PaymentsApi.java
    PaymentView.java
    internal/PaymentInstructionEntity.java
    internal/PaymentInstructionRepository.java
    internal/PaymentsFacade.java
  reconciliation/
    package-info.java
    BatchStatus.java
    ResultType.java
    ResolutionStatus.java
    StatementUpload.java
    ReconciliationBatchView.java
    ReconciliationResultView.java
    ReconciliationApi.java
    internal/
      ParsedStatement.java
      StatementCsvParser.java
      ReconciliationBatchEntity.java
      ChannelStatementEntryEntity.java
      ReconciliationResultEntity.java
      ReconciliationResolutionEntity.java
      ReconciliationBatchRepository.java
      ChannelStatementEntryRepository.java
      ReconciliationResultRepository.java
      ReconciliationResolutionRepository.java
      ReconciliationStore.java
      ReconciliationImportService.java
      ReconciliationMatcher.java
      ReconciliationRunner.java
      ReconciliationFacade.java
    web/ReconciliationWebController.java
src/main/resources/
  db/migration/V7__create_reconciliation_tables.sql
  templates/admin/reconciliation-list.html
  templates/admin/reconciliation-import.html
  templates/admin/reconciliation-detail.html
  static/css/admin.css
src/test/java/io/github/user32694/ledgerplatform/
  ModularityTest.java
  MigrationIntegrationTest.java
  reconciliation/internal/StatementCsvParserTest.java
  reconciliation/ReconciliationModuleTest.java
  reconciliation/ReconciliationWebTest.java
examples/channel-statement.csv
README.md
docs/USER_GUIDE.md
```

### Task 1: Create the module and database contract

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V7__create_reconciliation_tables.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/package-info.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/BatchStatus.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ResultType.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ResolutionStatus.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java`

- [ ] **Step 1: Write failing module and migration tests**

Change the module assertion to include `reconciliation`, verify its sole allowed dependency is `payments`, and add this migration assertion:

```java
@Test
void createsReconciliationTablesWithDatabaseConstraints() {
    assertThat(jdbcTemplate.queryForList("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'reconciliation'
            ORDER BY table_name
            """, String.class)).containsExactly(
                    "channel_statement_entry",
                    "reconciliation_batch",
                    "reconciliation_resolution",
                    "reconciliation_result");

    assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO reconciliation.reconciliation_batch
                (id, source_type, file_name, file_sha256, status, created_by, created_at)
            VALUES (?, 'OTHER', 'bad.csv', ?, 'IMPORTED', 'test', CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), "0".repeat(64)))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./mvnw -Dtest=ModularityTest,MigrationIntegrationTest test
```

Expected: `ModularityTest` cannot find `reconciliation`, and the migration test reports that the schema does not exist.

- [ ] **Step 3: Add Commons CSV and the module enums**

Add this dependency to `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-csv</artifactId>
    <version>1.14.1</version>
</dependency>
```

Create the module declaration and exact enum values:

```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = "payments")
package io.github.user32694.ledgerplatform.reconciliation;
```

```java
public enum BatchStatus {
    IMPORTED, RUNNING, COMPLETED, IMPORT_FAILED, RECONCILIATION_FAILED
}
```

```java
public enum ResultType {
    MATCHED, AMOUNT_MISMATCH, CHANNEL_ONLY, INTERNAL_ONLY
}
```

```java
public enum ResolutionStatus {
    NOT_REQUIRED, OPEN, RESOLVED
}
```

- [ ] **Step 4: Add the complete V7 migration**

```sql
CREATE SCHEMA reconciliation;

CREATE TABLE reconciliation.reconciliation_batch (
    id UUID PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL CHECK (source_type = 'SYNTHETIC_CHANNEL'),
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL UNIQUE,
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL CHECK (status IN
        ('IMPORTED', 'RUNNING', 'COMPLETED', 'IMPORT_FAILED', 'RECONCILIATION_FAILED')),
    total_rows INTEGER NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    matched_rows INTEGER NOT NULL DEFAULT 0 CHECK (matched_rows >= 0),
    difference_rows INTEGER NOT NULL DEFAULT 0 CHECK (difference_rows >= 0),
    error_message VARCHAR(2000),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_reconciliation_batch_period CHECK (
        (status = 'IMPORT_FAILED' AND period_start IS NULL AND period_end IS NULL)
        OR (status <> 'IMPORT_FAILED' AND period_start IS NOT NULL
            AND period_end IS NOT NULL AND period_start <= period_end))
);

CREATE TABLE reconciliation.channel_statement_entry (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    line_number INTEGER NOT NULL CHECK (line_number >= 2),
    channel_transaction_id VARCHAR(64) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (batch_id, line_number)
);

CREATE TABLE reconciliation.reconciliation_result (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    statement_entry_id UUID REFERENCES reconciliation.channel_statement_entry(id),
    payment_id UUID,
    result_type VARCHAR(32) NOT NULL CHECK (result_type IN
        ('MATCHED', 'AMOUNT_MISMATCH', 'CHANNEL_ONLY', 'INTERNAL_ONLY')),
    resolution_status VARCHAR(16) NOT NULL CHECK (resolution_status IN
        ('NOT_REQUIRED', 'OPEN', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (batch_id, statement_entry_id),
    UNIQUE (batch_id, payment_id),
    CONSTRAINT ck_reconciliation_result_refs CHECK (
        (result_type = 'INTERNAL_ONLY' AND statement_entry_id IS NULL AND payment_id IS NOT NULL)
        OR (result_type = 'CHANNEL_ONLY' AND statement_entry_id IS NOT NULL AND payment_id IS NULL)
        OR (result_type IN ('MATCHED', 'AMOUNT_MISMATCH')
            AND statement_entry_id IS NOT NULL AND payment_id IS NOT NULL)),
    CONSTRAINT ck_reconciliation_result_resolution CHECK (
        (result_type = 'MATCHED' AND resolution_status = 'NOT_REQUIRED')
        OR (result_type <> 'MATCHED' AND resolution_status IN ('OPEN', 'RESOLVED')))
);

CREATE TABLE reconciliation.reconciliation_resolution (
    id UUID PRIMARY KEY,
    result_id UUID NOT NULL UNIQUE REFERENCES reconciliation.reconciliation_result(id),
    action VARCHAR(32) NOT NULL CHECK (action = 'RESOLVE'),
    note VARCHAR(2000) NOT NULL CHECK (length(trim(note)) > 0),
    operator VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reconciliation_batch_created
    ON reconciliation.reconciliation_batch(created_at DESC, id DESC);
CREATE INDEX idx_statement_entry_batch
    ON reconciliation.channel_statement_entry(batch_id, occurred_at, channel_transaction_id);
CREATE INDEX idx_reconciliation_result_batch
    ON reconciliation.reconciliation_result(batch_id, result_type, resolution_status);
```

- [ ] **Step 5: Run tests and verify GREEN**

Run `./mvnw -Dtest=ModularityTest,MigrationIntegrationTest test`.

Expected: both classes pass and Flyway reports schema version `7`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/db/migration/V7__create_reconciliation_tables.sql \
  src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java \
  src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java
git commit -m "feat: add reconciliation schema and module"
```

### Task 2: Expose reconciliation candidates from payments

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentView.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentsApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionEntity.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentsFacade.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`

- [ ] **Step 1: Write the failing candidate-range test**

Add a test that creates two successful top-ups, reads their `occurredAt`, and asserts inclusive range selection and input validation:

```java
@Test
void findsOnlySuccessfulTopUpsInInclusiveCompletionRange() {
    var account = accountsApi.create("Reconciliation Candidate");
    var first = paymentsApi.topUp(new TopUpCommand("candidate-1", account.id(), 100));
    var second = paymentsApi.topUp(new TopUpCommand("candidate-2", account.id(), 200));

    assertThat(paymentsApi.findSucceededTopUps(first.occurredAt(), second.occurredAt()))
            .extracting(PaymentView::id)
            .containsExactly(first.id(), second.id());
    assertThatThrownBy(() -> paymentsApi.findSucceededTopUps(second.occurredAt(), first.occurredAt()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Start must not be after end");
}
```

- [ ] **Step 2: Run the test and verify RED**

Run `./mvnw -Dtest=TopUpModuleTest#findsOnlySuccessfulTopUpsInInclusiveCompletionRange test`.

Expected: compilation fails because `occurredAt` and `findSucceededTopUps` do not exist.

- [ ] **Step 3: Add the minimal public query**

Append `Instant occurredAt` to `PaymentView` and add this method to `PaymentsApi`:

```java
List<PaymentView> findSucceededTopUps(Instant fromInclusive, Instant toInclusive);
```

Expose the entity's `completedAt` as `occurredAt`, then add the repository query:

```java
@Query("""
        SELECT payment
        FROM PaymentInstructionEntity payment
        WHERE payment.paymentType = 'TOP_UP'
          AND payment.status = 'SUCCEEDED'
          AND payment.completedAt BETWEEN :fromInclusive AND :toInclusive
        ORDER BY payment.completedAt ASC, payment.id ASC
        """)
List<PaymentInstructionEntity> findSucceededTopUps(
        @Param("fromInclusive") Instant fromInclusive,
        @Param("toInclusive") Instant toInclusive);
```

Implement the facade method with null checks, `fromInclusive <= toInclusive`, mapping through `toView`, and deterministic repository ordering. Existing `PaymentView` construction must pass `payment.completedAt()` as its final argument.

- [ ] **Step 4: Run the payments and web regressions**

Run:

```bash
./mvnw -Dtest=TopUpModuleTest,AdminWebTest test
```

Expected: both classes pass; existing top-up pages remain unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments \
  src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java
git commit -m "feat: expose payment reconciliation candidates"
```

### Task 3: Parse and validate the fixed CSV contract

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ParsedStatement.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/StatementCsvParser.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/StatementCsvParserTest.java`

- [ ] **Step 1: Write parser tests for the valid contract and every rejection class**

Use one parameterized test for these invalid inputs: wrong header, no rows, blank ID, ID over 64 code points, zero/negative/non-numeric/overflow amount, invalid timestamp, empty row, missing/extra field, duplicate ID, malformed UTF-8, and row 10,001. The valid assertion is:

```java
@Test
void parsesValidCsvAndNormalizesTimeToUtc() {
    var parsed = parser.parse("""
            channel_transaction_id,amount_cents,occurred_at
            CH-1,12500,2026-01-15T17:30:00+08:00
            CH-2,7500,2026-01-15T10:45:00Z
            """.getBytes(StandardCharsets.UTF_8));

    assertThat(parsed.entries()).containsExactly(
            new ParsedStatement.Entry(2, "CH-1", 12500, Instant.parse("2026-01-15T09:30:00Z")),
            new ParsedStatement.Entry(3, "CH-2", 7500, Instant.parse("2026-01-15T10:45:00Z")));
    assertThat(parsed.periodStart()).isEqualTo(Instant.parse("2026-01-15T09:30:00Z"));
    assertThat(parsed.periodEnd()).isEqualTo(Instant.parse("2026-01-15T10:45:00Z"));
}
```

Each invalid case must assert `IllegalArgumentException` and an English message containing the physical CSV line and field when a field is involved.

- [ ] **Step 2: Run parser tests and verify RED**

Run `./mvnw -Dtest=StatementCsvParserTest test`.

Expected: compilation fails because the parser types do not exist.

- [ ] **Step 3: Implement the immutable parser result**

```java
record ParsedStatement(List<Entry> entries, Instant periodStart, Instant periodEnd) {
    ParsedStatement {
        entries = List.copyOf(entries);
    }

    record Entry(int lineNumber, String channelTransactionId, long amountCents, Instant occurredAt) {}
}
```

- [ ] **Step 4: Implement the parser with Commons CSV**

`StatementCsvParser` must use a `CharsetDecoder` configured with `CodingErrorAction.REPORT`, reject a UTF-8 BOM, configure Commons CSV without silently accepting missing columns, compare `parser.getHeaderNames()` to the exact three-name list, count physical records from line 2, and stop with `Statement exceeds 10000 rows` before returning row 10,001. Parse time with `OffsetDateTime.parse(value).toInstant()`, use `Long.parseLong`, and detect duplicate IDs with a `HashSet<String>`.

The only public operation inside the module is:

```java
ParsedStatement parse(byte[] content)
```

Do not trim channel IDs before duplicate detection; reject leading/trailing whitespace as invalid so the stored match key remains exact.

- [ ] **Step 5: Run parser tests and verify GREEN**

Run `./mvnw -Dtest=StatementCsvParserTest test`.

Expected: all parser cases pass without starting Spring or PostgreSQL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal
git commit -m "feat: validate synthetic channel statements"
```

### Task 4: Import batches atomically and idempotently

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/StatementUpload.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationBatchView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationApi.java`
- Create: four entity files and four repository files listed in File Structure
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationImportService.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationFacade.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`

- [ ] **Step 1: Write failing module tests for atomic import**

Cover a valid two-row import, same-hash replay, invalid-row failure metadata with zero entries, same channel ID in another file, and SHA-256 lowercase hex. Use these public types:

```java
public record StatementUpload(String fileName, byte[] content, String operator) {
    public StatementUpload {
        content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }
}
```

```java
public interface ReconciliationApi {
    ReconciliationBatchView importStatement(StatementUpload upload);
    List<ReconciliationBatchView> findBatches();
    ReconciliationBatchView getBatch(UUID batchId);
}
```

Use this complete batch view throughout the API, runner, and controller:

```java
public record ReconciliationBatchView(
        UUID id,
        String sourceType,
        String fileName,
        String fileSha256,
        Instant periodStart,
        Instant periodEnd,
        BatchStatus status,
        int totalRows,
        int matchedRows,
        int differenceRows,
        String errorMessage,
        String createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {}
```

The idempotency assertion must compare IDs and verify one batch and two statement rows through `JdbcTemplate`. The invalid assertion must verify status `IMPORT_FAILED`, a nonblank error message, zero statement rows, and a second identical upload returning the same failed batch ID.

- [ ] **Step 2: Run the module test and verify RED**

Run `./mvnw -Dtest=ReconciliationModuleTest test`.

Expected: compilation fails because the public reconciliation API does not exist.

- [ ] **Step 3: Implement entity state without public setters**

Map every V7 column. Use package-private constructors/factories, `@Version` on the batch, `EnumType.STRING` for enums, and only these batch transitions:

```java
void start(Instant now) {
    if (status != BatchStatus.IMPORTED && status != BatchStatus.RECONCILIATION_FAILED) {
        throw new IllegalStateException("Batch cannot start from " + status);
    }
    status = BatchStatus.RUNNING;
    startedAt = now;
    completedAt = null;
    errorMessage = null;
}

void complete(int matchedRows, int differenceRows, Instant now) {
    requireRunning();
    this.status = BatchStatus.COMPLETED;
    this.matchedRows = matchedRows;
    this.differenceRows = differenceRows;
    this.completedAt = now;
}

void failReconciliation(String message, Instant now) {
    requireRunning();
    status = BatchStatus.RECONCILIATION_FAILED;
    errorMessage = message;
    completedAt = now;
}
```

`ReconciliationResultEntity.resolve` must reject `MATCHED`, reject a blank note, reject a second call, set `RESOLVED`, and return a new resolution event. No entity exposes a mutation method for payment IDs, statement fields, amounts, or timestamps.

- [ ] **Step 4: Implement the non-transactional import orchestrator and transactional store**

`ReconciliationImportService.importStatement` must validate file name (nonblank, at most 255), content (non-null and non-empty), and operator (nonblank, at most 128), calculate SHA-256, return `store.findByHash(hash)` when present, then parse.

Use separate Spring bean methods so transaction boundaries are effective:

```java
@Transactional
ReconciliationBatchEntity persistImported(
        StatementUpload upload, String hash, ParsedStatement parsed) {
    var batch = batchRepository.save(ReconciliationBatchEntity.imported(
            upload.fileName(), hash, parsed.periodStart(), parsed.periodEnd(),
            parsed.entries().size(), upload.operator(), clock.instant()));
    entryRepository.saveAll(parsed.entries().stream()
            .map(entry -> ChannelStatementEntryEntity.from(batch.id(), entry))
            .toList());
    return batch;
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
ReconciliationBatchEntity persistImportFailure(
        StatementUpload upload, String hash, String errorMessage) {
    return batchRepository.save(ReconciliationBatchEntity.importFailed(
            upload.fileName(), hash, errorMessage, upload.operator(), clock.instant()));
}
```

On parsing or persistence failure, persist a stable message capped at 2,000 characters. If a unique hash race occurs, return the winning existing batch. If another batch owns a channel ID, return `IMPORT_FAILED`; no rows from the rejected batch may remain. Never store the raw file bytes.

- [ ] **Step 5: Run import tests and verify GREEN**

Run `./mvnw -Dtest=ReconciliationModuleTest test`.

Expected: import tests pass against PostgreSQL, including rollback and idempotency counts.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java
git commit -m "feat: import reconciliation batches atomically"
```

### Task 5: Run exact matching with safe lifecycle transitions

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationResultView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationMatcher.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunner.java`
- Modify: reconciliation entities, repositories, store, facade, API, and module test

- [ ] **Step 1: Write failing tests for all result types and lifecycle rules**

Create successful top-ups through `PaymentsApi`, build a CSV containing one exact match, one amount mismatch, and one unknown channel reference, and choose bounds that also include one omitted successful top-up. Assert:

```java
var completed = reconciliationApi.run(batch.id());

assertThat(completed.status()).isEqualTo(BatchStatus.COMPLETED);
assertThat(completed.matchedRows()).isEqualTo(1);
assertThat(completed.differenceRows()).isEqualTo(3);
assertThat(reconciliationApi.findResults(batch.id(), null, null))
        .extracting(ReconciliationResultView::resultType)
        .containsExactly(
                ResultType.AMOUNT_MISMATCH,
                ResultType.CHANNEL_ONLY,
                ResultType.INTERNAL_ONLY,
                ResultType.MATCHED);
```

Also assert: `IMPORT_FAILED` cannot run; a completed rerun returns the same batch and result IDs; only `SUCCEEDED + TOP_UP` candidates inside inclusive bounds are considered; a forced completion-store exception yields `RECONCILIATION_FAILED`; retry removes stale results and completes; and two starts cannot both create results.

- [ ] **Step 2: Run matching tests and verify RED**

Run `./mvnw -Dtest=ReconciliationModuleTest test`.

Expected: compilation fails because run/result operations do not exist.

- [ ] **Step 3: Add the public result query contract**

Extend `ReconciliationApi` with:

```java
ReconciliationBatchView run(UUID batchId);
List<ReconciliationResultView> findResults(
        UUID batchId, ResultType resultType, ResolutionStatus resolutionStatus);
```

Use this view shape so the UI never accesses entities:

```java
public record ReconciliationResultView(
        UUID id,
        UUID batchId,
        UUID statementEntryId,
        UUID paymentId,
        String channelTransactionId,
        Long channelAmountCents,
        Long internalAmountCents,
        Instant occurredAt,
        ResultType resultType,
        ResolutionStatus resolutionStatus,
        String resolutionNote,
        String resolvedBy,
        Instant resolvedAt) {}
```

- [ ] **Step 4: Implement the pure matcher**

Give `ReconciliationMatcher` one package-private method that takes parsed/persisted statement-entry snapshots and `List<PaymentView>` candidates and returns immutable result drafts. Build a `Map<String, PaymentView>` by `channelReference`; create one result per channel row, track consumed payment IDs, then append unconsumed candidates as `INTERNAL_ONLY`.

Define the matcher input/output types inside the class so persistence code does not depend on JPA entities:

```java
record StatementEntrySnapshot(
        UUID id, int lineNumber, String channelTransactionId,
        long amountCents, Instant occurredAt) {}

record ResultDraft(
        UUID statementEntryId, UUID paymentId, ResultType resultType,
        ResolutionStatus resolutionStatus, Instant occurredAt, String sortReference) {}
```

Every `MATCHED` draft uses `NOT_REQUIRED`; every difference draft uses `OPEN`. Sort with rank `0` for all differences and `1` for matched results, then `occurredAt`, then null-safe `sortReference`, then payment ID. Unit-test this class without Spring.

- [ ] **Step 5: Implement lifecycle transaction boundaries**

`ReconciliationRunner` is non-transactional and calls store methods on another bean:

```java
ReconciliationBatchView run(UUID batchId) {
    var started = store.markRunningOrReturnCompleted(batchId);
    if (started.status() == BatchStatus.COMPLETED) {
        return started;
    }
    try {
        var entries = store.findStatementEntries(batchId);
        var payments = paymentsApi.findSucceededTopUps(started.periodStart(), started.periodEnd());
        var drafts = matcher.match(entries, payments);
        return store.replaceResultsAndComplete(batchId, drafts);
    } catch (RuntimeException exception) {
        store.markReconciliationFailed(batchId, stableMessage(exception));
        throw exception;
    }
}
```

`markRunningOrReturnCompleted`, `replaceResultsAndComplete`, and `markReconciliationFailed` use `REQUIRES_NEW`. Catch optimistic-lock failure and re-read: return completed, reject running with `Batch is already running`, and never silently recompute. Result replacement and batch counters commit together.

Use this bounded failure helper so database text limits cannot hide the lifecycle state:

```java
private static String stableMessage(RuntimeException exception) {
    String detail = exception.getMessage() == null ? "" : ": " + exception.getMessage();
    String message = exception.getClass().getSimpleName() + detail;
    return message.substring(0, Math.min(message.length(), 2000));
}
```

- [ ] **Step 6: Run module, payments, and modularity tests**

Run:

```bash
./mvnw -Dtest=ReconciliationModuleTest,TopUpModuleTest,ModularityTest test
```

Expected: all tests pass and Modulith reports no entity-level dependency from reconciliation to payments.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java
git commit -m "feat: reconcile channel statements exactly"
```

### Task 6: Add audited resolution and the Chinese management workflow

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationApi.java`
- Modify: reconciliation result/entity/store/facade files
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationWebController.java`
- Create: three reconciliation templates listed in File Structure
- Modify: `src/main/resources/templates/admin/overview.html`
- Modify: `src/main/resources/templates/admin/accounts.html`
- Modify: `src/main/resources/templates/admin/account-form.html`
- Modify: `src/main/resources/templates/admin/topup-form.html`
- Modify: `src/main/resources/templates/admin/ledger.html`
- Modify: `src/main/resources/static/css/admin.css`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java`

- [ ] **Step 1: Write failing API resolution tests**

Extend the API with:

```java
ReconciliationResultView resolve(UUID resultId, String note, String operator);
```

Assert that a difference requires a nonblank note, records the exact authenticated operator and stripped note, changes `OPEN` to `RESOLVED`, creates one audit row, and rejects a second resolution or resolving `MATCHED`. A failed attempt must leave both result status and audit table unchanged.

- [ ] **Step 2: Write failing MockMvc workflow tests**

Cover anonymous redirect, admin list/import/detail pages, exact multipart upload field `file`, CSRF rejection, successful redirect to detail, invalid CSV Chinese feedback, run action, type/status filters, resolution with `authentication.getName()`, blank-note Chinese validation, and unknown UUID returning 404. Key assertions:

```java
mockMvc.perform(multipart("/admin/reconciliation/import")
        .file(new MockMultipartFile("file", "statement.csv", "text/csv", csv))
        .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/admin/reconciliation/*"));

mockMvc.perform(post("/admin/reconciliation/results/{id}/resolve", resultId)
        .with(csrf()).param("note", "已核对渠道凭证"))
        .andExpect(status().is3xxRedirection());
```

- [ ] **Step 3: Run API and web tests and verify RED**

Run `./mvnw -Dtest=ReconciliationModuleTest,ReconciliationWebTest test`.

Expected: compilation fails because resolution and web routes are missing.

- [ ] **Step 4: Implement resolution in one transaction**

Load the result with a pessimistic write lock, validate it is a difference and `OPEN`, call `resolve`, save the returned `ReconciliationResolutionEntity`, and flush. Map the joined audit fields into the returned view. Convert duplicate-resolution constraint failures into `IllegalStateException("Result is already resolved")`.

- [ ] **Step 5: Implement controller routes and stable Chinese presentation**

Use exactly these mappings:

```text
GET  /admin/reconciliation
GET  /admin/reconciliation/import
POST /admin/reconciliation/import
GET  /admin/reconciliation/{batchId}
POST /admin/reconciliation/{batchId}/run
POST /admin/reconciliation/results/{resultId}/resolve
```

Controller model attributes are `batches`, `batch`, `results`, `resultType`, `resolutionStatus`, `importError`, and `activeNav=reconciliation`. Map internal values with exhaustive switches and raw-value fallback helpers to these labels:

```text
IMPORTED=待对账, RUNNING=对账中, COMPLETED=已完成,
IMPORT_FAILED=导入失败, RECONCILIATION_FAILED=对账失败
MATCHED=匹配一致, AMOUNT_MISMATCH=金额不一致,
CHANNEL_ONLY=仅渠道存在, INTERNAL_ONLY=仅内部存在
NOT_REQUIRED=无需处理, OPEN=待处理, RESOLVED=已处理
```

Do not display exception messages directly. Map parser categories to `第 N 行的渠道流水号/金额/发生时间格式不正确`, duplicates to `渠道流水号重复`, and all other failures to `操作失败，请检查文件后重试`; log the English code and cause.

- [ ] **Step 6: Build the three compact templates and navigation**

The list page contains the title `自动对账`, an `导入渠道账单` command, and one un-nested table. The import page contains a `.csv` file input, fixed header text, source label `模拟渠道`, and `导入账单`. The detail page contains four summary metrics, a `开始对账` action only for runnable states, two filter selects, one result table, and an inline resolution form only for open differences.

Replace every existing disabled `自动对账` navigation item with an active link to `/admin/reconciliation`; keep `审计日志` disabled. Extend existing responsive CSS selectors rather than creating a second design system. At 390 px, filters and actions wrap, tables scroll inside `.table-wrap`, and the document itself has no horizontal overflow.

- [ ] **Step 7: Run web and security tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=ReconciliationModuleTest,ReconciliationWebTest,AdminWebTest test
```

Expected: all tests pass, visible reconciliation text is Chinese, CSRF is enforced, and existing administration pages still render.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/main/resources/templates/admin src/main/resources/static/css/admin.css \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java
git commit -m "feat: add Chinese reconciliation workflow"
```

### Task 7: Document, verify, and demonstrate the milestone

**Files:**
- Modify: `examples/channel-statement.csv`
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

- [ ] **Step 1: Write failing documentation assertions**

Assert the README lists five modules and no longer says reconciliation is unavailable. Assert the user guide contains the fixed CSV header, 10,000-row and 2 MB limits, the four Chinese result labels, retry behavior, and the rule that resolving a difference never edits payment or ledger facts.

- [ ] **Step 2: Run the documentation test and verify RED**

Run `./mvnw -Dtest=DocumentationTest test`.

Expected: assertions fail against the milestone-1 text.

- [ ] **Step 3: Update sample and operating instructions**

Keep `examples/channel-statement.csv` synthetic and valid. Document this exact workflow:

```text
1. Create an account and submit successful top-ups.
2. Copy their channel references into a local CSV with the fixed header.
3. Open 自动对账 -> 导入渠道账单 and upload the CSV.
4. Open the imported batch and choose 开始对账.
5. Filter differences and resolve one with a note.
6. Confirm the original payment and ledger pages are unchanged.
```

Explain that the file's earliest/latest timestamps define the internal candidate window and that uploading identical bytes returns the existing batch.

- [ ] **Step 4: Run the full build**

Run:

```bash
./mvnw clean verify
```

Expected: all unit, module, MockMvc, migration, and architecture tests pass with `BUILD SUCCESS`.

- [ ] **Step 5: Run browser acceptance on a fresh application process**

Start the built JAR on an available port with the configured PostgreSQL database. In an authenticated browser session, execute the fixture containing one match, one amount mismatch, one channel-only row, and one internal-only payment. Verify counters `1` matched and `3` differences, resolve one row, refresh, and confirm the audit fields persist.

Capture desktop and 390 px screenshots. Verify no browser console errors, every page has `<html lang="zh-CN">`, tables remain usable, and `document.documentElement.scrollWidth === document.documentElement.clientWidth` at both widths.

- [ ] **Step 6: Inspect the final diff and commit**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and only the intended documentation/sample files remain before the commit.

```bash
git add examples/channel-statement.csv README.md docs/USER_GUIDE.md \
  src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java
git commit -m "docs: explain automatic reconciliation workflow"
```

- [ ] **Step 7: Push the existing feature branch**

```bash
git push origin feature/foundation-ledger-topup
```

Expected: GitHub PR #1 updates with all reconciliation commits; do not merge until CI succeeds and the final diff has been reviewed.
