# Automatic Reconciliation Import and Matching Design

> Status: approved design
> Date: 2026-08-08

## 1. Goal

Add the first complete reconciliation workflow to the existing Spring Modulith
modular monolith:

```text
upload channel statement -> validate and import a batch -> run reconciliation
-> inspect deterministic results -> resolve differences with an audit record
```

The first source is one synthetic payment channel with a fixed CSV format. The
implementation must preserve the existing payment instructions and immutable
ledger entries. Reconciliation records facts about those systems; it does not
rewrite them.

## 2. Confirmed Scope

### In scope

- One source type: `SYNTHETIC_CHANNEL`.
- UTF-8 CSV with the exact header:
  `channel_transaction_id,amount_cents,occurred_at`.
- SHA-256 file idempotency.
- Atomic file validation and import.
- Synchronous, explicitly triggered reconciliation for a validated batch.
- Exact matching by channel reference and amount.
- Four result types: `MATCHED`, `AMOUNT_MISMATCH`, `CHANNEL_ONLY`, and
  `INTERNAL_ONLY`.
- Batch list, upload, detail, result filtering, and resolution pages.
- Append-only resolution audit records.
- Chinese user-facing labels and errors with English internal values.

### Out of scope

- Multiple channel adapters or configurable column mappings.
- Fuzzy matching by amount and time.
- Background workers, Spring Batch, Kafka, Redis, or microservices.
- Modifying or reversing a payment instruction or ledger entry.
- A public REST API for reconciliation.
- Multi-currency support; the existing domain remains CNY-only.

## 3. Architecture

Add a `reconciliation` application module with these responsibilities:

- `statement import`: read, validate, hash, and persist a channel batch.
- `batch lifecycle`: enforce batch transitions and idempotency.
- `matching engine`: compare imported entries with internal successful top-ups.
- `discrepancy resolution`: record an operator decision without changing facts.
- `web`: render the administration workflow.

The module may depend on the public `PaymentsApi` and the identity context. It
must not access payment or ledger JPA entities directly. The existing
`payments` module remains responsible for exposing the internal payment data
needed by the matcher, and `ledger` remains responsible for immutable posting.

```text
reconciliation -> PaymentsApi
payments       -> ledger
reconciliation -X-> payment/ledger entities
```

The first implementation remains a synchronous modular monolith. File size and
the existing 2 MB multipart limit keep the operation bounded. A later design
can replace the execution mechanism with a job or event without changing the
stored batch and result contract.

## 4. Input Contract and Import

The upload endpoint accepts one UTF-8 CSV file. Apache Commons CSV (or an
equivalent structured CSV parser) must be used instead of ad-hoc string
splitting.

Validation is performed before any statement row is persisted:

- The header must exactly match the three-column contract.
- There must be at least one data row.
- `channel_transaction_id` is required and at most 64 characters.
- `amount_cents` is a positive signed 64-bit integer.
- `occurred_at` is a valid ISO-8601 timestamp and is normalized to UTC.
- Empty rows, missing fields, extra fields, and duplicate channel transaction
  IDs fail validation.
- A maximum row count keeps synchronous processing bounded; the exact limit is
  defined in the implementation plan alongside the 2 MB file limit.

The service calculates SHA-256 before parsing. If the hash already belongs to
any batch status, the existing batch is returned without inserting another
batch or rows. A failed import remains a failed batch for that file; the
operator must correct the file and upload a new content hash.

Import is atomic:

1. Calculate the hash and perform the idempotency lookup.
2. Parse and validate every row in memory.
3. Insert the `IMPORTED` batch and all statement rows in one transaction.

If validation fails, persist only an `IMPORT_FAILED` batch in a separate short
transaction with the file name, hash, error summary, and timestamps. If the
valid import transaction fails, it rolls back completely before the same
failure metadata is persisted. No statement rows remain. A duplicate channel
transaction ID already present in another batch rejects the new batch.

## 5. Persistence Model

All tables are in a new `reconciliation` schema and are created by a new
Flyway migration.

### `reconciliation_batch`

- `id UUID PRIMARY KEY`
- `source_type VARCHAR(32) NOT NULL CHECK (source_type = 'SYNTHETIC_CHANNEL')`
- `file_name VARCHAR(255) NOT NULL`
- `file_sha256 CHAR(64) NOT NULL UNIQUE`
- `period_start TIMESTAMPTZ`
- `period_end TIMESTAMPTZ`
- `status VARCHAR(32) NOT NULL CHECK (status IN ('IMPORTED', 'RUNNING', 'COMPLETED', 'IMPORT_FAILED', 'RECONCILIATION_FAILED'))`
- `total_rows INTEGER NOT NULL DEFAULT 0`
- `matched_rows INTEGER NOT NULL DEFAULT 0`
- `difference_rows INTEGER NOT NULL DEFAULT 0`
- `error_message VARCHAR(2000)`
- `created_by VARCHAR(128) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- `started_at TIMESTAMPTZ`
- `completed_at TIMESTAMPTZ`
- `version BIGINT NOT NULL DEFAULT 0`

`period_start` and `period_end` are both null only for `IMPORT_FAILED`; every
other status requires both values and `period_start <= period_end`.

### `channel_statement_entry`

- `id UUID PRIMARY KEY`
- `batch_id UUID NOT NULL REFERENCES reconciliation_batch(id)`
- `line_number INTEGER NOT NULL`
- `channel_transaction_id VARCHAR(64) NOT NULL UNIQUE`
- `amount_cents BIGINT NOT NULL CHECK (amount_cents > 0)`
- `occurred_at TIMESTAMPTZ NOT NULL`
- `UNIQUE (batch_id, line_number)`

### `reconciliation_result`

- `id UUID PRIMARY KEY`
- `batch_id UUID NOT NULL REFERENCES reconciliation_batch(id)`
- `statement_entry_id UUID REFERENCES channel_statement_entry(id)`
- `payment_id UUID`
- `result_type VARCHAR(32) NOT NULL CHECK (result_type IN ('MATCHED', 'AMOUNT_MISMATCH', 'CHANNEL_ONLY', 'INTERNAL_ONLY'))`
- `resolution_status VARCHAR(16) NOT NULL CHECK (resolution_status IN ('NOT_REQUIRED', 'OPEN', 'RESOLVED'))`
- `created_at TIMESTAMPTZ NOT NULL`
- `UNIQUE (batch_id, statement_entry_id)`
- `UNIQUE (batch_id, payment_id)`

`payment_id` is a logical reference to the public payment view. It is not a
JPA association or a cross-module entity dependency. Original payment data is
not deleted or updated. `MATCHED` results use `NOT_REQUIRED`; the three
difference types start as `OPEN` and may move to `RESOLVED`.

### `reconciliation_resolution`

- `id UUID PRIMARY KEY`
- `result_id UUID NOT NULL REFERENCES reconciliation_result(id)`
- `action VARCHAR(32) NOT NULL CHECK (action = 'RESOLVE')`
- `note VARCHAR(2000) NOT NULL`
- `operator VARCHAR(128) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- `UNIQUE (result_id)`

Resolution events are append-only. The result's `resolution_status` is updated
in the same transaction to make filtering efficient; the event remains the
audit source for who resolved what and why. The unique result reference enforces
the first-version rule that a difference can be resolved only once.

## 6. Batch State and Reconciliation

Valid batch transitions:

```text
new upload -> IMPORTED
new upload -> IMPORT_FAILED
IMPORTED   -> RUNNING -> COMPLETED
IMPORTED   -> RUNNING -> RECONCILIATION_FAILED
RECONCILIATION_FAILED -> RUNNING -> COMPLETED
```

- Starting `RUNNING` or `COMPLETED` is rejected or treated as an idempotent
  read, respectively.
- An `IMPORT_FAILED` batch has no statement rows and cannot be run or retried;
  corrected content must be uploaded as a new hash.
- A `RECONCILIATION_FAILED` batch can be retried. Existing results from the
  failed attempt are removed in the retry transaction before new results are
  written.
- Optimistic locking prevents two operators from starting the same batch.
- A completed batch is never recomputed implicitly by a page refresh.

The matcher uses the statement file's inclusive minimum and maximum
`occurred_at` as the candidate range for internal payments. It queries only
`SUCCEEDED` `TOP_UP` payments in that range. Time bounds select candidates; the
actual match key is still the exact channel reference and amount.

For each channel statement entry:

1. Find an internal payment by `channel_transaction_id = channel_reference`.
2. If absent, create `CHANNEL_ONLY`.
3. If present with a different amount, create `AMOUNT_MISMATCH`.
4. If present with the same amount and a successful top-up, create `MATCHED`.

After channel entries are processed, every candidate internal payment not
referenced by a result becomes `INTERNAL_ONLY`.

Result queries use a deterministic order: differences first, then
`occurred_at`, then channel transaction ID. Batch counters are updated in the
same completion transaction.

## 7. Web Workflow

Routes are administration-only and protected by the existing security and CSRF
configuration:

```text
GET  /admin/reconciliation
GET  /admin/reconciliation/import
POST /admin/reconciliation/import
GET  /admin/reconciliation/{batchId}
POST /admin/reconciliation/{batchId}/run
POST /admin/reconciliation/results/{resultId}/resolve
```

Pages:

- Batch list: file name, import time, status, row count, matched count, and
  difference count.
- Upload page: file chooser, fixed format guidance, and import feedback.
- Batch detail: summary metrics, lifecycle action, and result table.
- Result filtering: result type and resolution status.
- Resolution form: required operator note and a single resolve action.

All visible labels, statuses, result types, and error messages are Chinese.
Routes, model attribute names, database values, and internal exception text stay
English. Unknown future enum values fall back to their raw internal value.

## 8. Error and Security Behavior

- Anonymous users are redirected to the existing login page.
- Non-admin users cannot access reconciliation pages or post actions.
- Missing CSRF tokens are rejected.
- File errors identify the line and field without storing the full source file.
- A failed batch exposes a stable Chinese presentation message, while logs keep
  the internal English error code and cause.
- A resolution requires a non-blank note and the authenticated operator name.
- A result already marked `RESOLVED` cannot be resolved a second time without a
  new explicit resolution action in a future design.

## 9. Testing and Verification

### Unit tests

- CSV header, field, timestamp, amount, empty-row, and duplicate validation.
- SHA-256 duplicate behavior.
- Matcher coverage for all four result types.
- Candidate date bounds and deterministic result ordering.
- Batch transition and retry rules.

### Integration and Web tests

- Atomic import rollback leaves no statement rows after a failed file.
- Duplicate channel IDs across batches are rejected.
- Completed batch reruns do not duplicate results.
- Failed reconciliation can be retried.
- MockMvc tests cover upload, list, detail, run, filter, resolve, CSRF, and
  Chinese feedback.
- PostgreSQL migration and constraint tests cover the new schema.

### Browser acceptance

- Upload a fixture containing one match, one amount mismatch, one channel-only
  row, and one internal-only payment.
- Start the batch and verify summary counts and result rows.
- Resolve a difference with a note and verify the audit record.
- Verify desktop and 390px mobile layouts, no document-level horizontal
  overflow, and no browser console errors.

## 10. Delivery Milestones

1. Schema, public payment query, CSV parser, and atomic import.
2. Matching engine, result persistence, batch lifecycle, and tests.
3. Chinese administration pages, filtering, and resolution audit.
4. Documentation, demo fixture, migration verification, CI, and browser QA.

The implementation is complete only when the full workflow can be demonstrated
from upload through resolution and `./mvnw clean verify` passes.
