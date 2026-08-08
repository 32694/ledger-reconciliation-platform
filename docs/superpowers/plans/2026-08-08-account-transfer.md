# Account Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an idempotent, double-entry account transfer workflow with PostgreSQL-backed concurrency protection and a Chinese administrator page.

**Architecture:** Keep transfers inside the existing `payments` Spring Modulith module. Submission creates a `TRANSFER` payment instruction; processing resolves both customer wallets and delegates one balanced `TRANSFER` journal to `LedgerApi`, whose sorted pessimistic locks and balance checks make the debit atomic. The web layer adds a dedicated transfer form while the existing payment list remains shared.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Modulith, Spring Data JPA, PostgreSQL 17, Flyway, Thymeleaf, MockMvc, JUnit, and ArchUnit.

---

### Task 1: Define the transfer API and persistence constraint

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/payments/TransferCommand.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentsApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/PaymentView.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionRepository.java`
- Create: `src/main/resources/db/migration/V8__add_transfer_payment_constraints.sql`
- Test: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java`

- [x] Add `TransferCommand(idempotencyKey, payerAccountId, payeeAccountId, amountCents)` and `PaymentsApi.transfer(...)`.
- [x] Extend `PaymentView` with payer and payee IDs, keeping top-up payer IDs null.
- [x] Add a repository insert query that accepts `paymentType`, payer, and payee while retaining `ON CONFLICT (idempotency_key) DO NOTHING`.
- [x] Add a Flyway check constraint requiring a payer for `TRANSFER`, forbidding a payer for `TOP_UP`, and rejecting equal payer/payee IDs.
- [x] Write tests that assert transfer instructions expose the command parties and the migration contains the transfer party constraint.
- [x] Run the focused tests and commit the API/schema contract.

### Task 2: Implement transfer submission, ledger posting, and failure states

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/ledger/LedgerInsufficientFundsException.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/ledger/internal/LedgerService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentSubmissionService.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentProcessor.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentsFacade.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentInstructionEntity.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`
- Test: `src/test/java/io/github/user32694/ledgerplatform/ledger/LedgerPersistenceTest.java`

- [x] Add failing tests for successful transfer, self-transfer rejection, insufficient funds, idempotent replay, and idempotency conflict.
- [x] Make `LedgerService.post` reject a liability wallet whose resulting balance is negative while preserving sorted pessimistic locking.
- [x] Reuse the submission idempotency lock and fingerprint logic for `TRANSFER` instructions.
- [x] Build a `TRANSFER` journal with debit on payer wallet and credit on payee wallet; map insufficient funds to `INSUFFICIENT_FUNDS` and overflow to the existing balance-limit reason.
- [x] Verify concurrent transfers from one funded payer cannot succeed for more than the starting balance.
- [x] Run payments, ledger, and module-boundary tests, then commit the domain behavior.

### Task 3: Add the Chinese transfer management page

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/web/PaymentsWebController.java`
- Create: `src/main/resources/templates/admin/transfer-form.html`
- Modify: `src/main/resources/templates/admin/layout.html`
- Modify: `src/main/resources/templates/admin/topup-form.html`
- Modify: `src/main/resources/templates/admin/overview.html`
- Test: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [x] Add `GET/POST /admin/payments/transfer` with CSRF-protected validation for payer, payee, amount, and idempotency key.
- [x] Render account selectors, a CNY cents amount field, and Chinese validation/business errors.
- [x] Add navigation and overview quick action while keeping the raw routes and domain states in English.
- [x] Extend recent payment rows with a Chinese type label so transfers and top-ups are distinguishable.
- [x] Test anonymous redirect, CSRF rejection, form validation, successful transfer redirect, and Chinese labels.
- [x] Run the full web test suite and commit the UI slice.

### Task 4: Document and verify the milestone

**Files:**
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

- [x] Document the transfer workflow, idempotency rules, insufficient-funds behavior, and concurrency guarantee.
- [x] Add a manual smoke-test sequence that creates two accounts, tops up the payer, transfers funds, and checks the ledger.
- [x] Run `./mvnw clean verify`, `git diff --check`, and verify the GitHub Actions result before opening or merging the next PR.
- [ ] Commit the documentation and push the feature branch.
