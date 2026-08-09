# Refund, Reversal, and Audit Design

> Status: approved design
> Date: 2026-08-08

## 1. Goal

Add full refunds for successful top-ups, full reversals for successful account
transfers, and an append-only business audit log to the existing modular
monolith. The original payment instruction and ledger transaction remain
immutable. Every successful reverse operation creates a linked payment
instruction and a new balanced journal.

## 2. Confirmed Scope

### In scope

- One full refund for a successful `TOP_UP`.
- One full reversal for a successful `TRANSFER`.
- A new payment instruction linked to the original instruction.
- Idempotent reverse commands and concurrent duplicate protection.
- Balance checks when money is taken back from a customer wallet.
- A required operator reason for each refund or reversal request.
- Append-only audit events for administrator business mutations.
- Payment detail, reverse-operation form, and audit list pages.
- Chinese user-facing labels and errors with English internal values.

### Out of scope

- Partial refunds, multiple successful refunds, or scheduled refunds.
- Reversing a failed or pending payment.
- Automatically adding funds to make a reverse operation succeed.
- Chargebacks, disputes, approval workflows, or maker-checker controls.
- Login and logout security auditing.
- Kafka, Redis, an outbox, background workers, or microservices.
- Changing previously applied Flyway migrations.

## 3. Architecture

The existing `payments` module remains the owner of payment lifecycle rules.
Refunds and reversals use the same submission, idempotency, processing, and
failure model as top-ups and transfers. The `ledger` module continues to expose
only immutable journal posting and balance queries.

Add an `audit` application module with a small public `AuditApi`. Business
modules call that API from the transaction that commits a business state
change. The audit module owns actor resolution, persistence, queries, and its
administration page; it does not access another module's entities.

```text
accounts       -> ledger, audit
payments       -> accounts, ledger, audit
reconciliation -> payments, audit
audit          -X-> accounts/payments/reconciliation entities
```

The design deliberately does not introduce asynchronous delivery. Audit rows
and successful state changes share the same PostgreSQL commit. Persisted
business failures, such as insufficient funds, record their audit outcome in
the same short transaction that marks the instruction `FAILED`.

## 4. Payment Model

`payment_instruction.payment_type` gains two values:

- `REFUND`: reverses a successful `TOP_UP`.
- `REVERSAL`: reverses a successful `TRANSFER`.

New fields:

- `original_payment_id UUID NULL REFERENCES payments.payment_instruction(id)`
- `operation_reason VARCHAR(500)`

Existing instructions have no original payment or operation reason. A refund
or reversal must have both. Its amount and account fields are copied from the
original instruction; callers cannot select a different amount or account.

Type rules:

| Type | Original | Stored parties | Ledger effect |
| --- | --- | --- | --- |
| `TOP_UP` | none | payee only | platform cash debit, customer credit |
| `TRANSFER` | none | payer and payee | payer debit, payee credit |
| `REFUND` | successful `TOP_UP` | original payee only | customer debit, platform cash credit |
| `REVERSAL` | successful `TRANSFER` | original payer and payee | original payee debit, original payer credit |

The existing payment status values remain `PENDING`, `SUCCEEDED`, and
`FAILED`. A reverse instruction can fail with the existing
`INSUFFICIENT_FUNDS` or `BALANCE_LIMIT_EXCEEDED` reasons. It never changes the
status of the original instruction.

## 5. Command and Processing Rules

Expose one command through `PaymentsApi`:

```java
reverse(ReversePaymentCommand command)
```

The command contains the original payment ID, a unique idempotency key, and a
required reason of at most 500 Unicode code points. The service loads the
original payment and derives `REFUND` or `REVERSAL`; the web layer does not
choose the reverse type.

Submission rules:

1. Validate the command and acquire the existing idempotency advisory lock.
2. Return the existing result when the key and request fingerprint match.
3. Reject the key when its fingerprint belongs to different input.
4. Lock the original payment row.
5. Require the original status to be `SUCCEEDED` and its type to be `TOP_UP`
   or `TRANSFER`.
6. Return the existing `PENDING` or `SUCCEEDED` reverse instruction if one
   already exists.
7. Insert a linked `PENDING` instruction using copied amount and parties. A
   concurrent uniqueness conflict is resolved by loading that existing active
   instruction instead of creating another one.
8. Post the inverse journal and complete or fail the instruction.

The reverse request fingerprint includes operation type, original payment ID,
reason, amount, accounts, and currency. Changing the reason while reusing a key
is an idempotency conflict.

The original row lock serializes competing submissions, while submission and
processing remain separate short transactions. A PostgreSQL partial unique
index on `original_payment_id` for `status IN ('PENDING', 'SUCCEEDED')`
provides the cross-transaction invariant: at most one active or successful
reverse instruction for an original payment. Concurrent callers reuse that
instruction and therefore process the same payment ID. Failed attempts leave
the index when their status changes to `FAILED`; after the source wallet is
funded, an operator may retry with a new idempotency key.

## 6. Ledger Behavior

Every successful reverse operation posts exactly one new journal with the
reverse instruction's channel reference as its unique business reference.

For a top-up refund:

```text
customer wallet  DEBIT   full original amount
platform cash    CREDIT  full original amount
```

For a transfer reversal:

```text
original payee wallet  DEBIT   full original amount
original payer wallet  CREDIT  full original amount
```

The ledger's stable account locking and non-negative liability balance rule are
reused unchanged. If the customer or original payee has spent the funds, the
reverse instruction becomes `FAILED / INSUFFICIENT_FUNDS` and no journal or
ledger entry is written.

## 7. Audit Module

Create `audit.audit_event` with these fields:

- `id UUID PRIMARY KEY`
- `actor VARCHAR(128) NOT NULL`
- `action VARCHAR(64) NOT NULL`
- `aggregate_type VARCHAR(64) NOT NULL`
- `aggregate_id VARCHAR(128) NOT NULL`
- `outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED'))`
- `summary VARCHAR(500) NOT NULL`
- `correlation_reference VARCHAR(128)`
- `occurred_at TIMESTAMPTZ NOT NULL`

The application exposes insert and read operations only. There is no update or
delete workflow. Actor resolution uses the authenticated Spring Security name
and falls back to `SYSTEM` for non-web execution and tests.

Actions covered in this milestone:

- `ACCOUNT_CREATE`
- `PAYMENT_TOP_UP`
- `PAYMENT_TRANSFER`
- `PAYMENT_REFUND`
- `PAYMENT_REVERSAL`
- `RECONCILIATION_IMPORT`
- `RECONCILIATION_RUN`
- `RECONCILIATION_RESOLVE`

The payment actions use the payment status as the audit outcome, so persisted
insufficient-funds failures are visible. Validation errors that never create or
change a business record are not audit events. Audit summaries contain stable,
human-readable business facts only. Passwords, session identifiers, CSRF
tokens, cookies, uploaded file content, and exception stack traces are never
stored.

## 8. Persistence Migrations

Add two forward-only Flyway migrations:

- `V9__add_payment_refunds_and_reversals.sql` expands the payment type column,
  adds the self-reference and reason, replaces the party/type check, and adds
  the partial unique index for one active or successful reverse operation.
- `V10__create_audit_events.sql` creates the `audit` schema, event table, and a
  descending `(occurred_at, id)` index.

V1 through V8 remain byte-for-byte unchanged. Migration integration tests must
prove old rows remain valid, invalid type/original combinations are rejected,
and the partial unique index permits failed retries but rejects a second
successful reverse instruction.

## 9. Administration Workflow

New protected routes:

```text
GET  /admin/payments/{paymentId}
GET  /admin/payments/{paymentId}/reverse
POST /admin/payments/{paymentId}/reverse
GET  /admin/audit
```

The recent payment table links each business reference to a detail page. The
detail page shows the type, status, amount, participants, reason, failure
reason, original payment link, and successful reverse-payment link when
present.

A successful top-up shows **发起全额退款**. A successful transfer shows
**发起全额冲正**. The form displays immutable original transaction facts and
accepts only the operation reason and idempotency key. A payment that is not
eligible shows its current state and does not render an active command button.

The audit page replaces the disabled navigation entry. It displays the
latest events in reverse chronological order and supports optional action and
outcome filters. The first version returns at most 100 rows and does not add a
general search engine or export.

All visible content is Chinese. Routes, form field names, Java types, database
columns, status values, and log messages stay English.

## 10. Error Behavior

- Unknown payment IDs on detail, form, or submission routes return HTTP 404
  with a Chinese not-found page.
- Pending, failed, refund, and reversal instructions cannot be reversed.
- An already successfully reversed original returns its linked result instead
  of moving money again.
- Reusing an idempotency key for different input shows the existing Chinese
  idempotency-conflict message.
- Insufficient funds shows **可退回余额不足，请补足资金后使用新幂等键重试**.
- A blank or overlong reason is rejected before submission.
- Constraint races are translated into the same deterministic domain result;
  raw SQL or stack-trace text is not shown in the page.

## 11. Testing and Verification

### Payment and ledger tests

- Successful top-up refund posts the exact inverse journal.
- Successful transfer reversal returns funds to the original payer.
- Failed or pending originals are rejected.
- A reverse instruction cannot itself be reversed.
- Same-key replay returns the same instruction without a second journal.
- Same-key different input is rejected.
- Insufficient funds writes no journal and allows a new-key retry later.
- Concurrent requests produce at most one successful reverse instruction.
- Existing top-up and transfer behavior remains unchanged.

### Audit tests

- Each in-scope successful mutation writes one audit event.
- Persisted payment failure writes a failed audit event.
- Business rollback does not leave a misleading success event.
- Actor, aggregate, correlation reference, outcome, and timestamp are stored.
- The public module exposes no update or delete operation.
- Action and outcome filters return deterministic newest-first results.

### Web and migration tests

- MockMvc covers detail, eligibility, reverse form validation, CSRF, Chinese
  feedback, linked transactions, audit navigation, and filters.
- PostgreSQL migration tests prove existing data remains valid and cover local
  type/original-field constraints plus the active-or-successful reverse unique
  index.
- Service tests cover cross-row original type and status rules that cannot be
  expressed by a PostgreSQL row check constraint.
- Spring Modulith and ArchUnit tests confirm only public module APIs are used.
- Browser acceptance covers the complete refund and reversal flows on desktop
  and 390px mobile layouts without overflow or console errors.

## 12. Delivery Criteria

The milestone is complete when an administrator can refund a top-up, reverse a
transfer, inspect both linked immutable journals, and trace the relevant action
in the audit page. The user guide and migration guide must explain the new
workflow and portability requirements. `./mvnw clean verify` and the browser
acceptance checks must pass before the branch is proposed for merge.
