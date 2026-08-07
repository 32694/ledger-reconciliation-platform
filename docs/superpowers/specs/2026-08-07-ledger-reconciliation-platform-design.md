# Ledger Reconciliation Platform Design

Date: 2026-08-07

## Purpose

Build a portfolio-quality simulated payment platform that demonstrates Java backend fundamentals: transaction boundaries, double-entry accounting, idempotency, concurrency control, reconciliation, modular architecture, security, testing, and operational documentation.

The first release is an operable backend with a compact administration UI. It uses synthetic data and must not be described as production-ready banking software.

## Goals

- Create and inspect CNY customer accounts.
- Submit idempotent top-ups and transfers.
- Record every successful money movement as an immutable, balanced ledger transaction.
- Prevent overdrafts under concurrent transfer requests.
- Import a CSV channel statement and classify reconciliation outcomes.
- Let an administrator record how reconciliation differences were resolved without mutating source facts.
- Provide a secure, server-rendered administration UI and REST API.
- Run reproducibly after migration by following the user guide.

## Non-goals

- Multiple currencies, exchange rates, fees, refunds, chargebacks, or interest.
- Real payment providers, bank connectivity, personal data, or company data.
- Customer-facing login, multiple administrator roles, or a full IAM system.
- Microservices, Kubernetes, Kafka, Redis, or a separate JavaScript frontend.
- Editing or deleting posted ledger entries.

## Architecture

The system is one Spring Boot 3.5 application targeting JDK 17. Spring Modulith defines and verifies module boundaries. Thymeleaf and HTMX render the administration interface from the same process. Maven Wrapper produces one executable JAR.

The top-level modules are:

| Module | Responsibility | Public dependency surface |
| --- | --- | --- |
| `identity` | Administrator credentials, login, and session | Spring Security integration only |
| `accounts` | Customer account lifecycle, status, and balance queries | `AccountsApi` |
| `payments` | Top-up and transfer instructions, idempotency, and payment state | `PaymentsApi`, payment events |
| `ledger` | Ledger accounts, immutable transactions and entries, balance calculation | `LedgerApi`, ledger events |
| `reconciliation` | CSV imports, duplicate detection, matching, results, and resolutions | `ReconciliationApi` |
| `audit` | Append-only records of administrator actions and domain events | Event listeners and query API |

Modules may use another module only through its root-package API or named interface. `ApplicationModules.verify()` fails the build for undeclared dependencies. Cross-module reactions that do not need an immediate return value use Spring application events.

## Accounting Model

The platform has one asset ledger account representing simulated platform cash. Each customer account owns one liability ledger account.

- Top-up: debit platform cash, credit customer wallet.
- Transfer: debit payer wallet, credit payee wallet.
- A liability wallet balance is credits minus debits.
- Each ledger transaction contains at least two positive entries in CNY.
- Total debit amount must equal total credit amount before persistence.
- A business reference can create at most one ledger transaction.
- Posted ledger transactions and entries have no update or delete use case.

The domain model validates balance before the repository writes the transaction. Database check constraints validate positive amounts and supported enum values; unique and foreign-key constraints validate identity and relationships.

## Payment Processing

A client supplies an idempotency key for each payment instruction.

1. The submission transaction inserts a `PENDING` payment instruction under a unique idempotency key.
2. Reusing the key with the same request returns the existing instruction. Reusing it with different request data returns an idempotency conflict.
3. Processing locks the payment instruction and payer customer account.
4. The service validates account status, different payer/payee accounts, positive CNY amount, and available balance.
5. The ledger atomically stores the balanced transaction and entries.
6. The payment becomes `SUCCEEDED`, and audit events are published after commit.
7. A business rejection becomes `FAILED` with a stable reason. An unexpected infrastructure failure leaves the instruction `PENDING`, so a retry can resume it.

The database lock and balance check occur inside the same PostgreSQL transaction. Concurrency tests must prove two transfers cannot spend the same balance.

## Reconciliation

The accepted CSV header is:

```csv
channel_transaction_id,amount_cents,occurred_at
```

`amount_cents` is a positive integer and `occurred_at` is an ISO-8601 instant. UTF-8 is required. A SHA-256 digest prevents importing an identical file twice. The first release accepts at most 10,000 data rows and processes the file synchronously.

The reconciliation module retains duplicate channel transaction IDs so it can classify them. For non-duplicate IDs, it matches a channel record to the local payment `channelReference` and produces exactly one of:

- `MATCHED`
- `LOCAL_ONLY`
- `CHANNEL_ONLY`
- `AMOUNT_MISMATCH`
- `DUPLICATE_CHANNEL_RECORD`

A resolution stores status, note, administrator, and resolution time. It never changes imported records, payment instructions, or ledger data.

## Data Model

The primary tables are:

- `identity.admin_user`
- `accounts.customer_account`
- `ledger.ledger_account`
- `ledger.ledger_transaction`
- `ledger.ledger_entry`
- `payments.payment_instruction`
- `reconciliation.reconciliation_batch`
- `reconciliation.channel_record`
- `reconciliation.reconciliation_result`
- `audit.audit_log`

UUIDs identify business entities. Human-readable account numbers and channel references have unique constraints. Money is stored as `BIGINT` cents. Timestamps are stored as UTC instants. Customer account rows use a version column and are locked pessimistically during outgoing transfers.

The ledger is the only source of truth for balances. `customer_account` does not contain an independently writable balance column.

## Administration UI

The UI uses a fixed left sidebar with these destinations: Overview, Accounts, Payments, Ledger, Reconciliation, and Audit Log.

The overview displays compact counts and totals, quick actions, recent payments, and unresolved reconciliation differences. Forms support creating an account, submitting a top-up, submitting a transfer, and uploading a channel statement. Lists use pagination and explicit status filters. HTMX updates form results and table fragments without introducing a separate frontend build.

The visual style is a restrained operational console: high information density, neutral surfaces, clear success/warning/error colors, no decorative dashboards, and no nested cards.

## Security

Spring Security form login protects every administration page and non-health API endpoint. Initial administrator credentials come from `APP_ADMIN_USERNAME` and `APP_ADMIN_PASSWORD`; the application refuses to bootstrap an administrator when either value is missing. Passwords are stored with an adaptive one-way hash.

The repository contains only `.env.example` placeholders. CSRF remains enabled for browser forms. Session cookies are HTTP-only and use secure settings when HTTPS is enabled. CSV uploads have row-count and request-size limits.

## Error Handling

REST errors use RFC 9457 `ProblemDetail` with a stable error code and request ID. HTMX forms display the same business errors next to the command that caused them.

Expected mappings are:

| Condition | HTTP status |
| --- | --- |
| Invalid field or malformed CSV | `422 Unprocessable Entity` |
| Missing resource | `404 Not Found` |
| Frozen account, insufficient funds, duplicate file, or idempotency conflict | `409 Conflict` |
| Missing or invalid authentication | `401 Unauthorized` or login redirect |
| Forbidden operation | `403 Forbidden` |
| Unexpected failure | `500 Internal Server Error` |

No error response contains credentials, SQL, stack traces, or local filesystem paths.

## Persistence and Runtime

PostgreSQL 17 is the only supported database. Flyway owns schema creation and migration. Local development on the company computer uses Homebrew PostgreSQL. GitHub Actions starts PostgreSQL 17 for integration tests. The documented personal-computer setup offers Docker Compose and an existing PostgreSQL installation.

No database data is committed. Configuration uses environment variables with non-secret defaults only where safe.

## Testing

- Pure Java unit tests cover money rules, accounting direction, balance calculation, payment state transitions, and reconciliation classification.
- Repository integration tests run against PostgreSQL and verify constraints, locks, and Flyway migrations.
- `@ApplicationModuleTest` tests each module and event-driven interaction.
- `ApplicationModules.verify()` enforces module boundaries and generates module documentation.
- MockMvc tests login, CSRF, form validation, HTMX fragments, and REST problem responses.
- Concurrency tests submit competing transfers and assert that successful debits never exceed the starting balance.
- CSV fixtures cover valid data, malformed rows, duplicate channel IDs, duplicate files, and every reconciliation result type.

Every behavior change follows red-green-refactor: add a failing test, observe the expected failure, write the smallest implementation, and run the relevant suite again.

## CI and Migration

GitHub Actions runs formatting checks, unit tests, module tests, PostgreSQL integration tests, and the package build. It never receives local administrator credentials or database contents.

The repository includes:

- `.env.example` with placeholder variables.
- `compose.yaml` for PostgreSQL 17.
- `docs/USER_GUIDE.md` covering prerequisites, configuration, database setup, startup, login, sample operations, CSV import, and troubleshooting.
- `docs/MIGRATION.md` covering clone-based setup, Git bundle recovery, PostgreSQL dump/restore, and secret recreation.

Git identity is configured only in the local repository. `.env`, IDE state, build output, local data, database dumps, and reference repositories are ignored. An offline Git bundle is generated after stable milestones but is not committed to the repository.

## Acceptance Criteria

The first release is complete when:

- A new user can follow `docs/USER_GUIDE.md` on a JDK 17 machine and start the application with PostgreSQL 17.
- An administrator can log in, create two customer accounts, top up one account, and transfer money between them.
- Every successful top-up and transfer has balanced immutable ledger entries.
- Repeating an idempotent request cannot create a second ledger transaction.
- Concurrent transfers cannot overdraw a wallet.
- Importing the sample channel statement produces all documented reconciliation categories.
- An administrator can resolve a difference while original source records remain unchanged.
- Module verification, all tests, and the Maven package build pass in GitHub Actions.
- A fresh clone contains no secret, company-specific address, absolute path, or real customer data.

## Reference Implementations

- [sivaprasadreddy/spring-modular-monolith](https://github.com/sivaprasadreddy/spring-modular-monolith): module APIs, dependency verification, module tests, Thymeleaf/HTMX, Flyway, PostgreSQL, and operational instrumentation.
- [xsreality/spring-modulith-with-ddd](https://github.com/xsreality/spring-modulith-with-ddd): domain-first package boundaries, Spring Modulith event tests, and security separation.
- [Apache Fineract](https://github.com/apache/fineract): accounting vocabulary and financial-domain reference only; its architecture and implementation are not used as a project scaffold.
