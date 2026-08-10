# Configurable Reconciliation Rules and Spring Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add versioned default/channel reconciliation rules and replace the in-process reconciliation runner with a restartable, chunk-oriented Spring Batch job that can demonstrate 100,000-row processing.

**Architecture:** Keep the existing Spring Boot modular monolith. The reconciliation module owns channels, immutable rule versions, job orchestration and a run-scoped work table; the payments module exposes bounded public paging queries. Two chunk steps create work results, and a final transaction promotes only successful work into the existing canonical result/case model.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Batch 5, Spring Data JPA, PostgreSQL 17, Flyway, Spring Modulith, Spring MVC, Thymeleaf, HTMX, JUnit 5, AssertJ, MockMvc, Maven Wrapper.

---

## Working Assumptions

- Execute from the existing worktree `outputs/ledger-reconciliation-platform/.worktrees/configurable-rules-spring-batch` on branch `feature/configurable-rules-spring-batch`.
- Keep all display text Chinese and all Java/SQL identifiers and enum constants English.
- Reconcile only successful `TOP_UP` payments, matching `channelTransactionId` to `channelReference` exactly.
- Store money and tolerance as integer cents. `abs(internal - channel) <= tolerance` is `MATCHED`.
- `queryWindowHours` symmetrically expands the statement period and never enables fallback matching.
- Use chunk size 500. Do not make chunk size user-configurable in this milestone.
- Preserve published Flyway files V1-V13 byte-for-byte. Add only V14 and V15.
- Preserve the existing canonical `reconciliation_result` and case lifecycle. Partial Batch output belongs only in `reconciliation_result_work`.

Use these environment overrides for database-backed tests on this machine:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test \
SPRING_DATASOURCE_USERNAME=fanyi32694 \
SPRING_DATASOURCE_PASSWORD=local-test \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 \
./mvnw test
```

## File Map

### Database and configuration

- Modify `pom.xml`: add Spring Batch runtime/test dependencies and the opt-in performance profile.
- Modify `src/main/resources/application.yml`: disable automatic Batch job launch and schema initialization; raise multipart limit to 20 MB.
- Modify `src/test/resources/application-test.yml`: use the same deterministic Batch settings in tests.
- Create `src/main/resources/db/migration/V14__add_reconciliation_rules_and_work_results.sql`: channels, rule versions, batch/run columns, work results, seeds and historical backfill.
- Create `src/main/resources/db/migration/V15__create_spring_batch_metadata.sql`: official PostgreSQL Spring Batch metadata schema under `batch`.

### Public reconciliation contracts

- Create `ReconciliationChannelView`, `ReconciliationRuleView`, `ReconciliationRuleVersionView`, `ReconciliationRuleDraftCommand`, `RuleScopeType`, and `RuleVersionStatus` in `src/main/java/io/github/user32694/ledgerplatform/reconciliation/`.
- Create `ReconciliationRulesApi` in the same package for channel and rule administration.
- Modify `StatementUpload`, `ReconciliationBatchView`, `ReconciliationRunView`, and `ReconciliationApi` for channel selection, locked rule details, progress and restart.

### Rule implementation

- Create focused channel/rule entities, repositories and `ReconciliationRuleService` under `reconciliation/internal`.
- Create `ReconciliationRuleWebController` and three rule templates under `reconciliation/web` and `templates/admin`.

### Batch implementation

- Create `ReconciliationResultWorkEntity`, repository, item records, readers, processor, writer, listeners, job configuration, launcher and recovery service under `reconciliation/internal`.
- Modify `ReconciliationStore`, `ReconciliationFacade`, `ReconciliationRunEntity`, `ReconciliationBatchEntity`, `ReconciliationImportService`, and repositories.
- Delete the superseded `ReconciliationRunner`, `ReconciliationTaskDispatcher`, and `ReconciliationExecutionConfig` after the Batch launcher is covered.

### Payments paging boundary

- Create `PaymentPage` and `PaymentPageCursor` in `payments/`.
- Modify `PaymentsApi`, `PaymentsFacade`, and `PaymentInstructionRepository` with bounded reference and keyset queries.

### Web, tests and docs

- Modify reconciliation controllers/templates, layout, CSS, module/web/migration/documentation tests, README and manuals.
- Create rule, Batch job, restart and opt-in performance tests plus a deterministic CSV generator script.

## Task 1: Add Database Contracts and Spring Batch Infrastructure

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Create: `src/main/resources/db/migration/V14__add_reconciliation_rules_and_work_results.sql`
- Create: `src/main/resources/db/migration/V15__create_spring_batch_metadata.sql`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java`

- [ ] **Step 1: Write migration assertions before creating the migrations**

Add a test that asserts the exact business tables, seed codes, required batch/run columns, work-result unique indexes, one draft per rule, and the immutable published-version trigger. Add a second assertion for all nine Spring Batch metadata tables:

```java
assertThat(jdbcTemplate.queryForList("""
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'batch' ORDER BY table_name
        """, String.class)).containsExactly(
        "batch_job_execution",
        "batch_job_execution_context",
        "batch_job_execution_params",
        "batch_job_execution_seq",
        "batch_job_instance",
        "batch_job_seq",
        "batch_step_execution",
        "batch_step_execution_context",
        "batch_step_execution_seq");

assertThat(jdbcTemplate.queryForList("""
        SELECT code FROM reconciliation.reconciliation_channel
        ORDER BY code
        """, String.class)).containsExactly(
        "ALIPAY", "LEGACY_SYNTHETIC", "UNION_PAY", "WECHAT_PAY");
```

Also insert a draft, publish it, then assert direct update and delete both throw `DataAccessException`.

- [ ] **Step 2: Run the migration test and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=MigrationIntegrationTest test
```

Expected: FAIL because V14/V15 tables do not exist.

- [ ] **Step 3: Add the managed Spring Batch dependencies and deterministic settings**

Add these dependencies without explicit versions:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.batch</groupId>
    <artifactId>spring-batch-test</artifactId>
    <scope>test</scope>
</dependency>
```

Add to both application configurations:

```yaml
spring:
  batch:
    job:
      enabled: false
    jdbc:
      initialize-schema: never
      table-prefix: batch.BATCH_
```

Change the production multipart limits to `20MB` for both file and request.

- [ ] **Step 4: Create V14 business tables and backfill**

The migration must create:

```sql
CREATE TABLE reconciliation.reconciliation_channel (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation.reconciliation_rule (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('DEFAULT', 'CHANNEL')),
    channel_id UUID REFERENCES reconciliation.reconciliation_channel(id),
    active_version_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_reconciliation_rule_scope CHECK (
        (scope_type = 'DEFAULT' AND channel_id IS NULL)
        OR (scope_type = 'CHANNEL' AND channel_id IS NOT NULL))
);

CREATE TABLE reconciliation.reconciliation_rule_version (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES reconciliation.reconciliation_rule(id),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    amount_tolerance_cents BIGINT NOT NULL CHECK (amount_tolerance_cents >= 0),
    query_window_hours INTEGER NOT NULL CHECK (query_window_hours BETWEEN 0 AND 168),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_by VARCHAR(128),
    published_at TIMESTAMPTZ,
    UNIQUE (rule_id, version_number)
);
```

Add partial unique indexes for one default rule, one channel rule, and one draft per rule. Add the `active_version_id` foreign key after the version table exists. Add a trigger that permits `DRAFT -> PUBLISHED` but rejects update/delete when `OLD.status = 'PUBLISHED'`.

Seed fixed UUID rows for `ALIPAY`, `WECHAT_PAY`, `UNION_PAY`, disabled `LEGACY_SYNTHETIC`, one default rule, one empty channel rule definition for each of the three selectable channels, and default published version 1 with tolerance 0 and window 0. Add `channel_id` and `rule_version_id` to every batch, backfill old rows to legacy/default, then make both columns non-null with those legacy/default UUIDs as database defaults. The defaults keep intermediate commits compatible with old insert code; Task 4 makes application writes explicit. Do not create a rule definition for `LEGACY_SYNTHETIC`.

Create `reconciliation_result_work` with `run_id`, `batch_id`, nullable statement/payment IDs, result type/status, timestamps, reference-shape constraints, and partial unique indexes for non-null statement/payment IDs. Add run columns `batch_job_instance_id`, `batch_job_execution_id`, `current_step`, `processed_items`, `total_items`, and `restart_count` with non-negative checks.

- [ ] **Step 5: Create V15 from the managed Spring Batch PostgreSQL schema**

Copy the Spring Batch 5 classpath resource `org/springframework/batch/core/schema-postgresql.sql` verbatim into V15, prepend `CREATE SCHEMA batch;`, and qualify every `BATCH_*` table and sequence as `batch.BATCH_*`. Do not rename columns, constraints or sequences; Spring Batch DAO SQL depends on them.

- [ ] **Step 6: Run migration and context verification**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=MigrationIntegrationTest,ApplicationContextTest test
```

Expected: PASS; Hibernate validates the old entities and Flyway reports V14/V15 applied.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/resources/application-test.yml src/main/resources/db/migration/V14__add_reconciliation_rules_and_work_results.sql src/main/resources/db/migration/V15__create_spring_batch_metadata.sql src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java
git commit -m "feat: 建立对账规则与批处理数据结构"
```

## Task 2: Implement Immutable Rule Versions and Channel Administration

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/RuleScopeType.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/RuleVersionStatus.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationChannelView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRuleView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRuleVersionView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRuleDraftCommand.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRulesApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationChannelEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationChannelRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleVersionEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleVersionRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditAction.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRuleModuleTest.java`

- [ ] **Step 1: Define the public contract in a failing module test**

The API contract is:

```java
public interface ReconciliationRulesApi {
    List<ReconciliationChannelView> findChannels(boolean includeInactive);
    ReconciliationChannelView setChannelActive(String channelCode, boolean active, String operator);
    List<ReconciliationRuleView> findRules();
    ReconciliationRuleView getRule(UUID ruleId);
    ReconciliationRuleVersionView saveDraft(UUID ruleId, ReconciliationRuleDraftCommand command);
    ReconciliationRuleVersionView publish(UUID ruleId, String operator);
    List<ReconciliationRuleVersionView> findVersions(UUID ruleId);
    ReconciliationRuleVersionView resolvePublishedVersion(String channelCode);
}

public record ReconciliationRuleDraftCommand(
        long amountToleranceCents,
        int queryWindowHours,
        String operator) {}

public record ReconciliationChannelView(
        UUID id, String code, String displayName, boolean active, long version) {}

public record ReconciliationRuleVersionView(
        UUID id, UUID ruleId, RuleScopeType sourceScope, String channelCode,
        int versionNumber, RuleVersionStatus status,
        long amountToleranceCents, int queryWindowHours,
        String createdBy, Instant createdAt, String publishedBy, Instant publishedAt) {}

public record ReconciliationRuleView(
        UUID id, RuleScopeType scopeType, String channelCode, String channelDisplayName,
        ReconciliationRuleVersionView activeVersion,
        ReconciliationRuleVersionView draft, long version) {}
```

Test the seeded channels, permanent legacy channel exclusion from `findChannels`, default fallback, channel override, one mutable draft, immutable published versions, `0..168` window validation, non-negative tolerance, channel enable/disable audit, publish audit, and two concurrent publish attempts producing one current version.

- [ ] **Step 2: Run the rule test and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationRuleModuleTest test
```

Expected: test compilation fails because the rule API does not exist.

- [ ] **Step 3: Implement focused entities and repositories**

Use JPA `@Version` on channel and rule entities. Repository operations must include `findByCode`, `findDefault`, `findByChannelId`, `findByIdForUpdate`, `findDraftByRuleId`, `findPublishedByRuleIdOrderByVersionNumberDesc`, and `findAllByRuleIdOrderByVersionNumberDesc`.

The published resolver must execute exactly:

```java
ReconciliationRuleVersionEntity resolvePublishedVersion(String channelCode) {
    var channel = requireChannel(channelCode);
    return ruleRepository.findByChannelId(channel.id())
            .flatMap(this::findActiveVersion)
            .orElseGet(() -> ruleRepository.findDefault()
                    .flatMap(this::findActiveVersion)
                    .orElseThrow(() -> new IllegalStateException("No published reconciliation rule")));
}

private Optional<ReconciliationRuleVersionEntity> findActiveVersion(ReconciliationRuleEntity rule) {
    return Optional.ofNullable(rule.activeVersionId()).flatMap(versionRepository::findById);
}
```

Treat a missing `activeVersionId` as no channel override and fall back to default. Reject inactive channels before resolving.

- [ ] **Step 4: Implement draft and publish transactions**

`saveDraft` validates the command and updates the existing draft or copies the current version into a new draft. `publish` locks the rule row, requires a draft, invokes `draft.publish(operator, now)`, flushes it, then updates `activeVersionId`. Add `RECONCILIATION_RULE_DRAFT_SAVE`, `RECONCILIATION_RULE_PUBLISH`, and `RECONCILIATION_CHANNEL_STATUS_CHANGE` audit actions.

- [ ] **Step 5: Run rule and audit tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationRuleModuleTest,AuditModuleTest test
```

Expected: PASS; database mutation of a published row also fails in the integration assertion from Task 1.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation src/main/java/io/github/user32694/ledgerplatform/audit/AuditAction.java src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRuleModuleTest.java
git commit -m "feat: 增加渠道对账规则版本管理"
```

## Task 3: Add the Chinese Rule Management Page

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationRuleWebController.java`
- Create: `src/main/resources/templates/admin/reconciliation-rules.html`
- Create: `src/main/resources/templates/admin/reconciliation-rule-edit.html`
- Create: `src/main/resources/templates/admin/reconciliation-rule-history.html`
- Modify: `src/main/resources/templates/admin/layout.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java`

- [ ] **Step 1: Write failing MockMvc tests for the complete workflow**

Assert anonymous redirect, CSRF rejection, Chinese list labels, edit form values, invalid tolerance/window feedback, draft save, publish confirmation, published history, and channel enable/disable. Required routes:

```text
GET  /admin/reconciliation/rules
GET  /admin/reconciliation/rules/{ruleId}/edit
POST /admin/reconciliation/rules/{ruleId}/draft
POST /admin/reconciliation/rules/{ruleId}/publish
GET  /admin/reconciliation/rules/{ruleId}/history
POST /admin/reconciliation/channels/{channelCode}/status
```

- [ ] **Step 2: Run the focused Web test and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationWebTest test
```

Expected: FAIL with 404 responses for rule routes.

- [ ] **Step 3: Implement the controller and amount conversion**

Parse the page amount with `new BigDecimal(raw).movePointRight(2).longValueExact()`. Reject more than two decimal places, negative values, overflow, blank operator, and window outside 0-168. Convert stored cents with `BigDecimal.valueOf(cents, 2).toPlainString()`.

- [ ] **Step 4: Build the three server-rendered pages**

The list shows default plus channel rows, active version, tolerance, window, draft state, channel status, edit/publish/history commands. The publish form includes the exact pending parameters and CSRF. Add a sidebar entry named `对账规则`; reuse existing table, form, status, button and responsive CSS classes instead of introducing a new visual system.

- [ ] **Step 5: Run Web and responsive tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationWebTest,AdminResponsiveCssTest test
```

Expected: PASS with all visible rule text in Chinese.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationRuleWebController.java src/main/resources/templates/admin src/main/resources/static/css/admin.css src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java
git commit -m "feat: 增加中文对账规则管理页面"
```

## Task 4: Make Statement Import Channel-Aware and Raise the Bounded Limit

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/StatementUpload.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationBatchView.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationBatchEntity.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationImportService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/StatementCsvParser.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationWebController.java`
- Modify: `src/main/resources/templates/admin/reconciliation-import.html`
- Create: `src/main/resources/templates/admin/fragments/reconciliation-rule-preview.html`
- Modify: `src/main/resources/templates/admin/reconciliation-list.html`
- Modify: `src/main/resources/templates/admin/reconciliation-detail.html`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/StatementCsvParserTest.java`

- [ ] **Step 1: Change tests to require a channel and a locked rule**

Use this upload contract everywhere:

```java
new StatementUpload("ALIPAY", "statement.csv", content, "admin")
```

Add tests that an active channel imports with the resolved rule version, an inactive/unknown channel is rejected, a channel override beats default, publishing later does not change the batch, and invalid CSV failures still retain channel/rule IDs. Update every existing constructor call mechanically; do not add a compatibility constructor that silently selects a channel.

- [ ] **Step 2: Change the parser boundary test to 100,000 rows and verify RED**

Build 100,001 rows and expect `CSV exceeds 100000 data rows at line 100002`. Run:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=StatementCsvParserTest,ReconciliationModuleTest,ReconciliationWebTest test
```

Expected: compilation failures for the new upload signature and failing row-limit assertion.

- [ ] **Step 3: Resolve and persist the rule before parsing**

`ReconciliationImportService` must resolve the channel and immutable published version before CSV parsing, pass both IDs into success and failure persistence, and keep hash idempotency. A repeated identical file returns its original batch even if a different channel is submitted later; document this existing file-level behavior in the test name.

- [ ] **Step 4: Update batch views and rule-aware candidate range**

Expose `channelCode`, `channelDisplayName`, `ruleVersionId`, `ruleVersionNumber`, `amountToleranceCents`, and `queryWindowHours` in `ReconciliationBatchView`. Add helper methods that compute:

```java
Instant queryStart() { return periodStart.minus(queryWindowHours, ChronoUnit.HOURS); }
Instant queryEnd() { return periodEnd.plus(queryWindowHours, ChronoUnit.HOURS); }
```

Only call these for successful imports with non-null periods.

Update `ReconciliationFacade.findResults`, `toResultView` and case lookup to load successful payments from `queryStart()` through `queryEnd()`, so an `INTERNAL_ONLY` result remains displayable when it lies in the configured expansion outside the original CSV period.

- [ ] **Step 5: Update upload and batch pages**

Render active channels in a required `<select name="channelCode">`. Use HTMX `GET /admin/reconciliation/import/rule-preview?channelCode=ALIPAY` to show whether the channel rule or default rule will be locked. Show channel and rule version on list/detail pages. Change the help text to `最大 20 MB，最多 100,000 行数据`.

- [ ] **Step 6: Run focused tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=StatementCsvParserTest,ReconciliationModuleTest,ReconciliationWebTest test
```

Expected: PASS; imported batches carry immutable channel/rule IDs.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation src/main/resources/templates/admin src/test/java/io/github/user32694/ledgerplatform/reconciliation
git commit -m "feat: 按渠道锁定账单对账规则"
```

## Task 5: Add Bounded Payments Paging APIs

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentPageCursor.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentPage.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentsApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentsFacade.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionRepository.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`

- [ ] **Step 1: Write paging and bounded-reference tests**

Define these methods:

```java
Map<String, PaymentView> findSucceededTopUpsByReferences(
        Set<String> references, Instant fromInclusive, Instant toInclusive);
PaymentPage findSucceededTopUpsAfter(
        Instant fromInclusive, Instant toInclusive, PaymentPageCursor after, int limit);
long countSucceededTopUps(Instant fromInclusive, Instant toInclusive);
```

Test exact reference filtering, time boundaries, exclusion of failed/non-top-up payments, stable `(completedAt,id)` ordering across equal timestamps, empty reference sets without SQL, and limit validation `1..500`.

- [ ] **Step 2: Run payment tests and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=TopUpModuleTest test
```

Expected: compilation fails because paging types and methods are absent.

- [ ] **Step 3: Implement keyset repository queries**

Use one query for the first page and one for pages after a cursor. Both order by `completedAt ASC, id ASC`; the cursor predicate is:

```sql
payment.completedAt > :afterTime
OR (payment.completedAt = :afterTime AND payment.id > :afterId)
```

Reference lookup must use `channelReference IN :references` plus the same type/status/time predicates. Convert results to an insertion-order map and throw if duplicate references violate the existing uniqueness invariant.

- [ ] **Step 4: Run payments and modularity tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=TopUpModuleTest,ModularityTest test
```

Expected: PASS; reconciliation will be able to page through the public payments boundary.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java
git commit -m "feat: 提供对账所需支付分页查询"
```

## Task 6: Implement Rule-Aware Work Results and Pure Matching

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationWorkItem.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationWorkResult.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleMatcher.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationResultWorkEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationResultWorkRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRuleMatcherTest.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationWorkStoreTest.java`

- [ ] **Step 1: Write pure matcher boundary tests**

The processor input contains either one statement entry plus an optional exact-reference payment, or one payment candidate plus its consumed flag. Assert `CHANNEL_ONLY`, `MATCHED` below/equal tolerance, and `AMOUNT_MISMATCH` above tolerance. A second-step unconsumed payment creates `INTERNAL_ONLY`; a consumed payment returns `null` so Spring Batch counts it as read but does not write a duplicate result.

```java
assertThat(matcher.process(statement(100), payment(101), 1).resultType())
        .isEqualTo(ResultType.MATCHED);
assertThat(matcher.process(statement(100), payment(102), 1).resultType())
        .isEqualTo(ResultType.AMOUNT_MISMATCH);
```

- [ ] **Step 2: Write failing work-store idempotency tests**

Insert the same work result twice for a run and assert one row. Insert the same statement/payment for another run and assert it is allowed. Assert failed work never appears from `findResults(batchId)`. Assert promotion copies the current run exactly once into canonical results and removes all work rows for that batch.

- [ ] **Step 3: Run tests and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationRuleMatcherTest,ReconciliationWorkStoreTest test
```

Expected: compilation fails because work types do not exist.

- [ ] **Step 4: Implement the pure matcher and JDBC batch writer boundary**

Both imported and payment amounts are positive. Compute the difference as `Math.max(left, right) - Math.min(left, right)`; this cannot overflow for two positive `long` values. Unit-test the boundary with `1` and `Long.MAX_VALUE`.

Persist work with `JdbcTemplate.batchUpdate` and `ON CONFLICT DO NOTHING`; do not call `saveAll` per item. Promotion runs in one transaction: delete any canonical result rows for the failed batch, insert from the selected run work rows, update batch/run totals, write audit, then delete batch work rows.

- [ ] **Step 5: Run focused tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationRuleMatcherTest,ReconciliationWorkStoreTest test
```

Expected: PASS with no canonical rows before promotion.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal
git commit -m "feat: 增加幂等对账工作结果"
```

## Task 7: Build the Two-Step Chunk Job

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/StatementMatchItemReader.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/InternalOnlyPaymentItemReader.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationWorkItemProcessor.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationWorkItemWriter.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationJobConfiguration.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ChannelStatementEntryRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationBatchJobTest.java`

- [ ] **Step 1: Write a failing two-step Job integration test**

Create statement/payment fixtures for all four result types with a non-zero tolerance and window. Launch `reconciliationJob` with a known `runId`; assert step names, `COMPLETED` statuses, read/write counts, canonical result counts, batch summary and zero work rows after finalization.

- [ ] **Step 2: Run the Job test and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationBatchJobTest test
```

Expected: application context fails because `reconciliationJob` is absent.

- [ ] **Step 3: Implement checkpoint-aware readers**

`StatementMatchItemReader` pages statement rows by `lineNumber` in groups of 500, performs one `findSucceededTopUpsByReferences` call per page, buffers self-contained work items, and stores `lastCommittedLineNumber` in `ExecutionContext`.

`InternalOnlyPaymentItemReader` pages payments by `PaymentPageCursor`, asks the work store for consumed payment IDs in one query per page, and emits every payment with a `consumed` flag. The processor returns `null` for consumed payments and an `INTERNAL_ONLY` work result otherwise. This makes the step read count equal the candidate count used in `totalItems`. The reader stores cursor time/ID in `ExecutionContext`; `open` restores the cursor and `update` writes only the last item returned to a committed chunk.

- [ ] **Step 4: Configure four deterministic steps**

Build `prepareReconciliationStep`, `matchStatementEntriesStep`, `findInternalOnlyPaymentsStep`, and `finalizeReconciliationStep` with `JobRepository` and `PlatformTransactionManager`. Both chunk steps use `.chunk(500, transactionManager)`, the work writer, and `.faultTolerant().retry(TransientDataAccessException.class).retryLimit(3)`. Do not configure `skip`.

Let Spring Boot create `JobRepository`, `JobExplorer` and `JobOperator`; do not add `@EnableBatchProcessing`, because doing so would bypass Boot's `batch.BATCH_` table-prefix configuration.

- [ ] **Step 5: Run Job, matcher and payment tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationBatchJobTest,ReconciliationRuleMatcherTest,TopUpModuleTest test
```

Expected: PASS; each page crosses the payments module only through `PaymentsApi`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationBatchJobTest.java
git commit -m "feat: 使用分块任务执行自动对账"
```

## Task 8: Replace the Local Runner with Batch Launch, Progress and Restart

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationJobLauncher.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationJobExecutionListener.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationChunkProgressListener.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationJobRecovery.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRunView.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationFacade.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunEntity.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Delete: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunner.java`
- Delete: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationTaskDispatcher.java`
- Delete: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationExecutionConfig.java`
- Delete: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationTaskDispatcherTest.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationJobLauncherTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRecoveryTest.java`

- [ ] **Step 1: Write lifecycle, progress and restart tests**

Add `restartRun(UUID runId, String operator)` to `ReconciliationApi`. Test:

- start returns `QUEUED` and launches asynchronously;
- a second start returns the same active run;
- chunk commits make `processedItems` increase monotonically and never exceed `totalItems`;
- a failed JobExecution leaves the same run `FAILED` with stable error text;
- restart uses the same JobInstance/run/attempt, increments `restartCount`, and transitions `FAILED -> RUNNING -> SUCCEEDED`;
- batch-level retry after failure creates a new run and attempt number but keeps the locked rule;
- a `QUEUED` orphan is submitted at startup;
- a stale `RUNNING` execution is ended and restarted once;
- a second automatic failure is retained for manual action.

- [ ] **Step 2: Run lifecycle tests and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationModuleTest,ReconciliationRunRecoveryTest test
```

Expected: compilation or assertions fail because the old dispatcher marks orphans failed and cannot restart checkpoints.

- [ ] **Step 3: Configure an asynchronous TaskExecutorJobLauncher**

Use a bounded `ThreadPoolTaskExecutor` only as the Spring Batch launch executor: core 2, max 4, queue 50, prefix `reconciliation-batch-`. Configure `TaskExecutorJobLauncher` with the shared `JobRepository`. The web layer never calls the Job directly.

Launch with exactly one identifying parameter:

```java
new JobParametersBuilder()
        .addString("runId", runId.toString(), true)
        .toJobParameters();
```

- [ ] **Step 4: Implement listeners and domain transitions**

`beforeJob` records JobInstance/JobExecution IDs and moves `QUEUED` or restartable `FAILED` to `RUNNING`. The chunk listener updates current step and committed counts in `REQUIRES_NEW`. `afterJob` marks failure only when the final promotion did not succeed. Stable error strings are capped at 2000 characters.

- [ ] **Step 5: Implement startup recovery with JobExplorer and JobOperator**

Capture startup time at construction. On `ApplicationReadyEvent`, process only application runs requested before that cutoff. Submit `QUEUED` runs without executions. For stale `RUNNING`, update the abandoned execution to a restartable failed state through `JobRepository`, then call `JobOperator.restart(executionId)`. Use `restartCount` to permit one automatic recovery.

- [ ] **Step 6: Delete superseded runner code and update tests**

Remove old executor bean, dispatcher and all-list matcher runner. Replace the dispatcher test with `ReconciliationJobLauncherTest` covering launch rejection and failure persistence. Keep the pure new matcher; remove `ReconciliationMatcher` only after no production references remain.

- [ ] **Step 7: Run lifecycle and modularity tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationModuleTest,ReconciliationBatchJobTest,ReconciliationRunRecoveryTest,ReconciliationJobLauncherTest,ModularityTest test
```

Expected: PASS; no `reconciliationTaskExecutor`, `ReconciliationRunner`, or `ReconciliationTaskDispatcher` references remain.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/io/github/user32694/ledgerplatform/reconciliation src/test/java/io/github/user32694/ledgerplatform/reconciliation
git commit -m "feat: 支持对账任务断点续跑"
```

## Task 9: Expose Progress and Restart in the Chinese Operations UI

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationWebController.java`
- Modify: `src/main/resources/templates/admin/reconciliation-detail.html`
- Modify: `src/main/resources/templates/admin/fragments/reconciliation-run-status.html`
- Modify: `src/main/resources/templates/admin/reconciliation-list.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/AdminResponsiveCssTest.java`

- [ ] **Step 1: Write failing UI behavior tests**

Assert the detail page shows channel, rule version, tolerance, window, current step, `已处理 / 总数`, percentage, Batch status and restart count. Assert failed runs show two distinct CSRF-protected actions: `从断点继续` posts the run ID, while `重新发起对账` posts the batch ID. Assert active fragments keep two-second HTMX polling and stop after terminal state.

- [ ] **Step 2: Run Web tests and verify RED**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationWebTest,AdminResponsiveCssTest test
```

Expected: FAIL because progress/restart controls are absent.

- [ ] **Step 3: Add restart route and deterministic progress formatting**

Add `POST /admin/reconciliation/runs/{runId}/restart`. Percentage is `0` when total is zero; otherwise use integer floor of `processed * 100 / total`, capped at 100. Map step names to `准备任务`, `匹配渠道账单`, `扫描内部单边`, and `汇总结果`.

- [ ] **Step 4: Update templates without nested cards**

Keep the status fragment stable in size so polling does not shift the page. Use a native `<progress>` element plus text. Use existing button styles and table layout; add only the CSS needed for the progress row and responsive wrapping.

- [ ] **Step 5: Run Web tests**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Dtest=ReconciliationWebTest,AdminWebTest,AdminResponsiveCssTest test
```

Expected: PASS; all controls and errors are Chinese.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationWebController.java src/main/resources/templates/admin src/main/resources/static/css/admin.css src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java src/test/java/io/github/user32694/ledgerplatform/AdminResponsiveCssTest.java
git commit -m "feat: 展示对账批处理进度与恢复操作"
```

## Task 10: Add the 100,000-Row Demonstration, Portability Docs and Final Verification

**Files:**
- Create: `scripts/generate-reconciliation-demo.sh`
- Create: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationPerformanceIT.java`
- Modify: `pom.xml`
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `docs/MIGRATION.md`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationImmutabilityTest.java`

- [ ] **Step 1: Write failing documentation assertions**

Replace obsolete claims about `2 MB`, `10,000`, the local thread pool and tasks that never resume. Require `20 MB`, `100,000`, Spring Batch, default/channel rules, immutable versions, checkpoint continuation, the two retry choices, V14/V15, and the opt-in performance command.

After V14/V15 SQL is final, run `shasum -a 256` for V13, V14 and V15 and add the three literal digests to `MigrationImmutabilityTest`. This locks all currently published migrations without changing the existing V1-V12 assertions.

- [ ] **Step 2: Run documentation tests and verify RED**

```bash
./mvnw -Dtest=DocumentationTest,MigrationImmutabilityTest test
```

Expected: FAIL because the manuals still describe the old executor and limits.

- [ ] **Step 3: Add the deterministic generator and opt-in profile**

The script accepts output path and row count, validates `1..100000`, writes the fixed header, and emits deterministic IDs, amounts and timestamps. It must use only POSIX shell plus `awk`, and write through a temporary file before moving to the requested output.

Add Maven profile `reconciliation-performance` that configures Maven Failsafe to run only `ReconciliationPerformanceIT`. The fixture uses row index modulo 10: 0 is matched, 1 is amount mismatch, and 2-9 are channel-only; insert a separate internal-only payment for every modulo-0 row. Expected totals are 10,000 matched, 10,000 amount mismatch, 80,000 channel-only and 10,000 internal-only. Inject one deterministic failure after at least two committed chunks, restart the same run, assert monotonic progress, zero duplicate results/work rows and `restartCount == 1`, then print elapsed milliseconds and rows/second. Do not assert a maximum duration.

- [ ] **Step 4: Update README and manuals**

Document JDK 17, PostgreSQL 17, Git, Maven Wrapper, Flyway-owned Batch schema, rule publication, channel selection, progress, checkpoint continuation, new-attempt retry, 100,000-row generation, performance profile, migration ordering and rollback boundary. Use relative paths and environment variables only; do not include this computer's username, Homebrew prefix or database credentials.

- [ ] **Step 5: Run the default verification suite**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw clean verify
git diff --check
```

Expected: Maven exits 0 with all tests passing; `git diff --check` prints nothing.

- [ ] **Step 6: Run the opt-in 100,000-row verification**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test SPRING_DATASOURCE_USERNAME=fanyi32694 SPRING_DATASOURCE_PASSWORD=local-test SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 ./mvnw -Preconciliation-performance -Dit.test=ReconciliationPerformanceIT verify
```

Expected: PASS and print elapsed time, throughput, final totals and restart count without a fixed time threshold.

- [ ] **Step 7: Start the application and perform browser acceptance**

Start on an unused port with documented environment variables. At desktop `1440x900` and mobile `390x844`, verify login, rule draft/publish/history, channel disable/enable, upload with rule preview, Batch progress, failure continuation, new-attempt retry, result cases and audit log. Check no console errors, no document-level horizontal overflow and no overlapping controls.

- [ ] **Step 8: Commit final docs and performance tooling**

```bash
git add pom.xml scripts/generate-reconciliation-demo.sh src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationPerformanceIT.java README.md docs/USER_GUIDE.md docs/MIGRATION.md src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java src/test/java/io/github/user32694/ledgerplatform/MigrationImmutabilityTest.java
git commit -m "docs: 完善批处理演示与迁移手册"
```

- [ ] **Step 9: Verify branch state before review**

```bash
git status --short --branch
git log --oneline origin/main..HEAD
```

Expected: clean worktree and one design commit plus the implementation commits listed in this plan. Do not push or open a pull request until the complete diff has been reviewed.
