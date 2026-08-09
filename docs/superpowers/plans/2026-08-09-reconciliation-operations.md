# Reconciliation Operations Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade synchronous reconciliation and one-step difference resolution into an asynchronous run history plus a Chinese exception operations workbench.

**Architecture:** Keep the existing Spring Modulith `reconciliation` module and exact CSV matcher. Add durable run-attempt records around an application-local bounded executor, add a locked `OPEN -> CLAIMED -> RESOLVED` exception state machine with append-only case events, and expose read models to Thymeleaf/HTMX pages and the overview.

**Tech Stack:** JDK 17, Spring Boot 3.5, Spring Modulith, Spring Data JPA, PostgreSQL 17, Flyway, Thymeleaf, HTMX 2, JUnit 5, AssertJ, MockMvc.

---

## File Map

**Create**

- `src/main/resources/db/migration/V12__add_reconciliation_operations.sql`: run attempts, case ownership, resolution codes, immutable case events, and history backfill.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/RunStatus.java`: public run state enum.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRunView.java`: immutable run read model.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ResolutionCode.java`: resolution conclusion enum.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationCaseEventView.java`: immutable timeline item.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationCaseView.java`: exception workbench row read model.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationCaseDetailsView.java`: one exception plus its ordered timeline.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationOperationsSummary.java`: overview counters.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunEntity.java`: run-attempt state machine.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRepository.java`: run history and active-run queries.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationCaseEventEntity.java`: append-only case action mapping.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationCaseEventRepository.java`: ordered timeline queries.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationExecutionConfig.java`: bounded task executor.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationTaskDispatcher.java`: submit work and handle executor rejection.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRecovery.java`: fail abandoned active runs at startup.
- `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationCaseWebController.java`: workbench and case actions.
- `src/main/resources/templates/admin/fragments/reconciliation-run-status.html`: HTMX status fragment.
- `src/main/resources/templates/admin/reconciliation-cases.html`: exception workbench.
- `src/main/resources/templates/admin/reconciliation-case-detail.html`: exception details and timeline.

**Modify**

- `ReconciliationApi`, `ResolutionStatus`, `ReconciliationResultView`, `AuditAction` for the new contract.
- `ReconciliationBatchEntity`, repositories, `ReconciliationResultEntity`, `ReconciliationResolutionEntity`, `ReconciliationStore`, `ReconciliationRunner`, and `ReconciliationFacade` for state and persistence.
- `ReconciliationWebController`, reconciliation list/detail templates, `OverviewController`, overview/layout templates, and `admin.css` for the Chinese workflow.
- migration, module, Web, overview, documentation, modularity, and audit tests.
- `README.md`, `docs/USER_GUIDE.md`, and `docs/MIGRATION.md` for usage and migration.

### Task 1: Add the V12 persistence contract

**Files:**
- Create: `src/main/resources/db/migration/V12__add_reconciliation_operations.sql`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationImmutabilityTest.java`

- [ ] **Step 1: Write failing migration tests**

Add assertions that a V11 fixture upgrades with one run table, `CLAIMED` support, a backfilled `OTHER` resolution code and one `RESOLVED` case event. Add update/delete attempts against `reconciliation_case_event`:

```java
assertThat(columnNames("reconciliation", "reconciliation_run"))
        .contains("attempt_number", "status", "requested_by", "error_message");
assertThat(checkDefinition("reconciliation", "reconciliation_result", "ck_reconciliation_result_resolution"))
        .contains("CLAIMED");
assertThatThrownBy(() -> jdbcTemplate.update(
        "UPDATE reconciliation.reconciliation_case_event SET actor = 'changed' WHERE id = ?", eventId))
        .isInstanceOf(DataAccessException.class);
```

- [ ] **Step 2: Verify the migration tests fail**

Run: `./mvnw -Dtest=MigrationIntegrationTest,MigrationImmutabilityTest test`

Expected: FAIL because Flyway V12 and the new tables/columns do not exist.

- [ ] **Step 3: Implement V12**

Create the run table and partial index, alter the result/resolution tables, backfill history, and protect case events:

```sql
CREATE TABLE reconciliation.reconciliation_run (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    requested_by VARCHAR(128) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    matched_rows INTEGER NOT NULL DEFAULT 0 CHECK (matched_rows >= 0),
    difference_rows INTEGER NOT NULL DEFAULT 0 CHECK (difference_rows >= 0),
    error_message VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (batch_id, attempt_number)
);
CREATE UNIQUE INDEX uq_reconciliation_run_active
    ON reconciliation.reconciliation_run(batch_id)
    WHERE status IN ('QUEUED', 'RUNNING');
```

Add `assigned_to`, `claimed_at`, and `version` to results, `resolution_code` to resolutions, and `reconciliation_case_event` with an `UPDATE OR DELETE` rejection trigger. Drop and recreate the named result-resolution check constraint with `CLAIMED` allowed only for differences.

- [ ] **Step 4: Verify migration behavior**

Run: `./mvnw -Dtest=MigrationIntegrationTest,MigrationImmutabilityTest test`

Expected: all migration and immutability tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V12__add_reconciliation_operations.sql \
  src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java \
  src/test/java/io/github/user32694/ledgerplatform/MigrationImmutabilityTest.java
git commit -m "feat: add reconciliation operations schema"
```

### Task 2: Model durable reconciliation runs

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/RunStatus.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationRunView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationBatchRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`

- [ ] **Step 1: Write failing run-state tests**

Cover first attempt, active-run idempotency and ordered history. Update terminal state through a package-private store fixture before creating attempt 2 so this task remains independent of asynchronous execution:

```java
var first = reconciliationApi.startRun(batch.id(), "admin");
var duplicate = reconciliationApi.startRun(batch.id(), "admin");
assertThat(duplicate.id()).isEqualTo(first.id());
assertThat(first.attemptNumber()).isOne();
assertThat(reconciliationApi.findRuns(batch.id())).extracting(ReconciliationRunView::attemptNumber)
        .containsExactly(1);
```

- [ ] **Step 2: Verify tests fail**

Run: `./mvnw -Dtest=ReconciliationModuleTest test`

Expected: compilation fails because the new API and run types are absent.

- [ ] **Step 3: Add run types and locked persistence operations**

Define:

```java
public enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

public record ReconciliationRunView(
        UUID id, UUID batchId, int attemptNumber, RunStatus status,
        String requestedBy, Instant requestedAt, Instant startedAt, Instant completedAt,
        int matchedRows, int differenceRows, String errorMessage) {}
```

Add `findByIdForUpdate` to `ReconciliationBatchRepository`, active-run and ordered-history queries to `ReconciliationRunRepository`, and `ReconciliationStore` methods `queueRun`, `markRunRunning`, `completeRun`, `failRun`, and `findRuns`. Normalize all timestamps to database microseconds. The public API starts dispatching only in Task 3.

- [ ] **Step 4: Verify run persistence tests pass**

Run: `./mvnw -Dtest=ReconciliationModuleTest test`

Expected: new run-state tests pass without changing Web behavior yet.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java
git commit -m "feat: record reconciliation run attempts"
```

### Task 3: Execute reconciliation asynchronously and recover abandoned runs

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationExecutionConfig.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationTaskDispatcher.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRecovery.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationFacade.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunner.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`

- [ ] **Step 1: Write failing async tests**

Use Awaitility to prove the request first returns `QUEUED`/`RUNNING`, eventually succeeds, records the requesting actor, fails cleanly on a database error, retries with attempt 2, and marks startup leftovers failed:

```java
var run = reconciliationApi.startRun(batch.id(), "operator-1");
await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(reconciliationApi.findRuns(batch.id()).get(0).status())
                .isEqualTo(RunStatus.SUCCEEDED));
assertThat(auditApi.findRecent(AuditAction.RECONCILIATION_RUN, null, 10))
        .singleElement().extracting(AuditEventView::actor).isEqualTo("operator-1");
```

- [ ] **Step 2: Verify async tests fail**

Run: `./mvnw -Dtest=ReconciliationModuleTest test`

Expected: FAIL because runs are not dispatched or recovered.

- [ ] **Step 3: Add the bounded executor and dispatcher**

Create a named `ThreadPoolTaskExecutor` with core 2, max 4, queue 50, and prefix `reconciliation-`. `ReconciliationTaskDispatcher.submit(runId)` calls `executor.execute(() -> runner.execute(runId))`; catch `TaskRejectedException` and persist a failed run.

- [ ] **Step 4: Refactor the runner around run IDs**

The runner must load the queued attempt, mark it running, execute the existing matcher, and atomically complete both batch and run. Failure uses a `REQUIRES_NEW` store method and retains the requesting actor for audit. Keep the existing synchronous `run(batchId)` API as a temporary compatibility wrapper so every intermediate commit compiles and passes; remove it after the Web route migrates in Task 6.

- [ ] **Step 5: Add startup recovery**

On `ApplicationReadyEvent`, update all remaining `QUEUED`/`RUNNING` attempts and their batches to failed with `Application restarted before run completion`.

- [ ] **Step 6: Verify async and legacy tests**

Run: `./mvnw -Dtest=ReconciliationModuleTest,ReconciliationWebTest test`

Expected: module and existing Web tests pass. No intermediate commit may leave the suite failing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java
git commit -m "feat: run reconciliation tasks asynchronously"
```

### Task 4: Add the locked exception lifecycle and immutable timeline

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ResolutionCode.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationCaseEventView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationCaseEventEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationCaseEventRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ResolutionStatus.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationResultView.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationResultEntity.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationResolutionEntity.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationFacade.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/audit/AuditAction.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`

- [ ] **Step 1: Write failing lifecycle tests**

Cover claim, same-actor idempotency, other-actor conflict, release, re-claim, owner-only resolution, required code/note, and event/audit order:

```java
var claimed = reconciliationApi.claim(difference.id(), "operator-1");
assertThat(claimed.resolutionStatus()).isEqualTo(ResolutionStatus.CLAIMED);
assertThat(claimed.assignedTo()).isEqualTo("operator-1");
assertThatThrownBy(() -> reconciliationApi.resolve(
        difference.id(), ResolutionCode.INTERNAL_CONFIRMED, "checked", "operator-2"))
        .hasMessageContaining("assigned to another operator");
```

- [ ] **Step 2: Verify lifecycle tests fail**

Run: `./mvnw -Dtest=ReconciliationModuleTest test`

Expected: compilation or assertions fail for missing lifecycle behavior.

- [ ] **Step 3: Implement result state transitions**

Add `CLAIMED` and fields `assignedTo`, `claimedAt`, `version`. Implement entity methods that enforce:

```java
OPEN + claim(actor) -> CLAIMED
CLAIMED + release(same actor) -> OPEN
CLAIMED + resolve(code, note, same actor) -> RESOLVED
```

Keep repeat claim by the same actor idempotent and reject every other transition.

Keep the existing `resolve(resultId, note, operator)` API temporarily as a compatibility wrapper that claims and resolves for the same operator with `ResolutionCode.OTHER`; Task 7 removes it after the old Web form is replaced.

- [ ] **Step 4: Persist case events and audit actions**

Create one immutable case event and one audit event per successful state change. Add `RECONCILIATION_CASE_CLAIM`, `RECONCILIATION_CASE_RELEASE`, and `RECONCILIATION_CASE_RESOLVE` to `AuditAction`. Do not emit events for idempotent repeats or rejected commands.

- [ ] **Step 5: Verify lifecycle behavior**

Run: `./mvnw -Dtest=ReconciliationModuleTest,AuditModuleTest test`

Expected: lifecycle, timeline, concurrency, and audit assertions pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/audit \
  src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java
git commit -m "feat: add reconciliation case lifecycle"
```

### Task 5: Add operations queries and summary metrics

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationCaseView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationCaseDetailsView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationOperationsSummary.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationResultRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationRunRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationFacade.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`

- [ ] **Step 1: Write failing query tests**

Create multiple batches and cases, then assert default active cases, type/status/assignee filters, newest-first timeline, and summary counters. Match rate is `(matched / (matched + differences)) * 100`, or `null` when no completed batch exists.

- [ ] **Step 2: Verify query tests fail**

Run: `./mvnw -Dtest=ReconciliationModuleTest test`

Expected: compilation fails for missing query read models.

- [ ] **Step 3: Implement explicit repository queries and mapping**

Use JPQL queries with nullable filters and deterministic ordering. Return `ReconciliationCaseView` for workbench rows without timelines and `ReconciliationCaseDetailsView` for one case plus its ordered timeline. Do not make the Web layer issue per-row repository calls.

- [ ] **Step 4: Verify query tests pass**

Run: `./mvnw -Dtest=ReconciliationModuleTest test`

Expected: filters, ordering, no-data metrics, and populated metrics pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java
git commit -m "feat: expose reconciliation operations queries"
```

### Task 6: Add asynchronous run controls and HTMX status refresh

**Files:**
- Create: `src/main/resources/templates/admin/fragments/reconciliation-run-status.html`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationWebController.java`
- Modify: `src/main/resources/templates/admin/reconciliation-list.html`
- Modify: `src/main/resources/templates/admin/reconciliation-detail.html`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java`

- [ ] **Step 1: Write failing Web tests**

Assert that POST run records the authenticated user, returns immediately, details show run history, active status uses `hx-get`/`hx-trigger="every 2s"`, and completed fragment returns `HX-Refresh: true`.

- [ ] **Step 2: Verify Web tests fail**

Run: `./mvnw -Dtest=ReconciliationWebTest test`

Expected: FAIL for absent run views and fragment route.

- [ ] **Step 3: Update controller routes**

POST `/{batchId}/run` calls `startRun(batchId, authentication.getName())`. GET `/{batchId}/run-status` renders the fragment while active and sets `HX-Refresh` once terminal. Invalid state errors redirect with a stable Chinese flash message.

After the route migrates, remove the temporary synchronous `run(batchId)` compatibility method and update all callers to the asynchronous contract.

- [ ] **Step 4: Update batch templates**

Show latest run, run history, retry action, matched/difference counts and failure text. Keep table containers horizontally scrollable and stop HTMX polling after a terminal state.

- [ ] **Step 5: Verify Web tests pass**

Run: `./mvnw -Dtest=ReconciliationWebTest,AdminWebTest test`

Expected: all reconciliation and shared admin Web tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/web \
  src/main/resources/templates/admin \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java
git commit -m "feat: add asynchronous reconciliation controls"
```

### Task 7: Build the Chinese exception workbench and case details

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/web/ReconciliationCaseWebController.java`
- Create: `src/main/resources/templates/admin/reconciliation-cases.html`
- Create: `src/main/resources/templates/admin/reconciliation-case-detail.html`
- Modify: `src/main/resources/templates/admin/layout.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java`

- [ ] **Step 1: Write failing workbench tests**

Assert Chinese labels, active-case defaults, all filters, unauthenticated redirects, CSRF protection, claim/release/resolve routes, owner restrictions, resolution code labels and timeline order.

- [ ] **Step 2: Verify workbench tests fail**

Run: `./mvnw -Dtest=ReconciliationWebTest test`

Expected: 404 or missing-content failures.

- [ ] **Step 3: Implement controller and view mapping**

Add `/admin/reconciliation/cases`, `/cases/{resultId}`, and POST claim/release/resolve routes. Map enum values to Chinese in Web-only methods; keep request values and domain values English.

Remove the temporary direct-resolve compatibility overload after the case actions and old tests migrate to explicit claim followed by resolve.

- [ ] **Step 4: Implement responsive templates**

Use an unframed page heading, one table section for the work queue, a definition grid for case details, and a single timeline section. Only show actions valid for the authenticated administrator and current state.

- [ ] **Step 5: Verify Web behavior**

Run: `./mvnw -Dtest=ReconciliationWebTest,AdminWebTest test`

Expected: all tests pass with no old direct-resolve form remaining.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/reconciliation/web \
  src/main/resources/templates/admin src/main/resources/static/css/admin.css \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationWebTest.java
git commit -m "feat: add Chinese reconciliation case workbench"
```

### Task 8: Add overview metrics and preserve module boundaries

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/OverviewController.java`
- Modify: `src/main/resources/templates/admin/overview.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`

- [ ] **Step 1: Write failing overview and architecture tests**

Assert the overview contains latest match rate, open, claimed and failed counts with links, and that `OverviewController` depends only on public module APIs.

- [ ] **Step 2: Verify tests fail**

Run: `./mvnw -Dtest=AdminWebTest,ModularityTest test`

Expected: missing model/content assertions fail.

- [ ] **Step 3: Inject the public reconciliation API and render metrics**

Extend `@ConditionalOnBean` and constructor injection with `ReconciliationApi`, obtain one summary read model, and render four compact linked metrics. Do not query reconciliation repositories from the root package.

- [ ] **Step 4: Verify overview and module tests pass**

Run: `./mvnw -Dtest=AdminWebTest,ModularityTest test`

Expected: overview and Spring Modulith/ArchUnit checks pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/OverviewController.java \
  src/main/resources/templates/admin/overview.html src/main/resources/static/css/admin.css \
  src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java
git commit -m "feat: show reconciliation operations metrics"
```

### Task 9: Document, verify, and visually accept the milestone

**Files:**
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `docs/MIGRATION.md`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

- [ ] **Step 1: Write failing documentation assertions**

Require the README and manuals to mention asynchronous runs, retry history, exception claim/release/resolve, resolution conclusions, HTMX refresh, V12 migration and migration rollback boundaries.

- [ ] **Step 2: Verify documentation tests fail**

Run: `./mvnw -Dtest=DocumentationTest test`

Expected: missing phrase assertions fail.

- [ ] **Step 3: Update user-facing documentation**

Document the exact Chinese click path and status meanings. State that the executor is local to one application process, active runs are failed on restart, and no automatic ledger adjustment occurs.

- [ ] **Step 4: Run focused and complete verification**

Run:

```bash
./mvnw -Dtest=ReconciliationModuleTest,ReconciliationWebTest,MigrationIntegrationTest,MigrationImmutabilityTest,AdminWebTest,AuditModuleTest,ModularityTest,DocumentationTest test
./mvnw clean verify
git diff --check
```

Expected: zero failures and zero errors; only the documented environment-dependent migration test may be skipped.

- [ ] **Step 5: Run browser acceptance**

Start on an unused local port with PostgreSQL credentials, then verify desktop `1440x900` and mobile `390x844`: run import/start/auto-refresh, run history, case claim/release/resolve, timeline, overview metrics, no console errors and no document-level horizontal overflow.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/USER_GUIDE.md docs/MIGRATION.md \
  src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java
git commit -m "docs: explain reconciliation operations workflow"
```

- [ ] **Step 7: Publish for review**

Push `feature/reconciliation-operations`, create a PR against `main`, and wait for GitHub Actions. Keep the worktree until CI and review are complete.
