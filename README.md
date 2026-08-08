# Ledger Reconciliation Platform

A simulated payment-ledger application built as a learning and portfolio project. It is not a production banking system and must not be used for real money, customer data, or regulated workloads.

## Milestone 2

The current milestone is a Spring Boot modular monolith with five modules:

- `identity`: bootstraps one configured administrator and provides form login.
- `accounts`: creates and lists simulated customer accounts and shows balances derived from the ledger.
- `ledger`: persists balanced, immutable journal entries and lists recent ledger transactions.
- `payments`: records idempotent simulated top-ups and account transfers.
- `reconciliation`: imports synthetic channel statements, runs exact-match reconciliation, and records auditable discrepancy resolutions.

The administrator interface supports **账户充值**, **账户转账**, ledger inspection, and **自动对账**. A transfer atomically writes one payer debit and one payee credit as a balanced journal. PostgreSQL row locks serialize concurrent debits from the same wallet, and the ledger rejects any transfer that would make a customer balance negative. This provides a concrete demonstration of idempotency, 双重记账, transaction rollback, and 并发 control.

Automatic reconciliation accepts the fixed synthetic CSV format documented in the [User Guide](docs/USER_GUIDE.md), creates an immutable import batch, and lets an administrator start reconciliation and resolve discrepancies. It currently compares channel records with successful top-ups only. Production channel integrations and a general-purpose audit log remain outside this milestone.

## Technology Stack

- Java 17, Spring Boot 3.5, and Spring Modulith
- Spring Data JPA with Hibernate, PostgreSQL 17, and Flyway migrations
- Thymeleaf, HTMX, Spring Security, and a responsive administrator interface
- JUnit 5, AssertJ, MockMvc, Spring Modulith tests, and ArchUnit
- Maven Wrapper, Docker Compose, and GitHub Actions

## Prerequisites

- macOS or another Unix-like development environment
- JDK 17
- PostgreSQL 17, installed with Homebrew or run with Docker Compose

## Quick Start

```sh
cp .env.example .env
# In .env, replace both password placeholders with single-quoted values.
# Keep each password on one line and do not include a single quote in the password itself.
set -a
source .env
set +a
docker compose up -d
./mvnw spring-boot:run
```

Open <http://localhost:8080/login>. The application does not load `.env` automatically; sourcing it before Compose replaces stale `DB_*` values in the current shell so PostgreSQL and the application use the same credentials. Creating the test database and running tests are covered in the [User Guide](docs/USER_GUIDE.md).

See the [User Guide](docs/USER_GUIDE.md) for complete setup and operation instructions and the [Migration Guide](docs/MIGRATION.md) for moving the project and optional local data to another computer.

## Architectural References

The project uses these repositories only as references for architecture and domain vocabulary; it does not copy their scaffolding:

- [sivaprasadreddy/spring-modular-monolith](https://github.com/sivaprasadreddy/spring-modular-monolith)
- [xsreality/spring-modulith-with-ddd](https://github.com/xsreality/spring-modulith-with-ddd)
- [Apache Fineract](https://github.com/apache/fineract)
