# Ledger Reconciliation Platform

A simulated payment-ledger application built as a learning and portfolio project. It is not a production banking system and must not be used for real money, customer data, or regulated workloads.

## Milestone 1

The current milestone is a Spring Boot modular monolith with four modules:

- `identity`: bootstraps one configured administrator and provides form login.
- `accounts`: creates and lists simulated customer accounts and shows balances derived from the ledger.
- `ledger`: persists balanced, immutable journal entries and lists recent ledger transactions.
- `payments`: records idempotent simulated top-ups into customer wallets.

Transfers, channel-statement import, reconciliation, discrepancy resolution, and an audit log are not implemented yet. [`examples/channel-statement.csv`](examples/channel-statement.csv) is synthetic input for a future reconciliation milestone; the current application cannot import it.

## Prerequisites

- macOS or another Unix-like development environment
- JDK 17
- PostgreSQL 17, installed with Homebrew or run with Docker Compose

## Quick Start

```sh
cp .env.example .env
# Replace every change-this-* placeholder in .env before continuing.
docker compose up -d
set -a
source .env
set +a
./mvnw spring-boot:run
```

Open <http://localhost:8080/login>. The application does not load `.env` automatically; the `source` commands export it to the application process. Creating the test database and running tests are covered in the [User Guide](docs/USER_GUIDE.md).

See the [User Guide](docs/USER_GUIDE.md) for complete setup and operation instructions and the [Migration Guide](docs/MIGRATION.md) for moving the project and optional local data to another computer.

## Architectural References

The project uses these repositories only as references for architecture and domain vocabulary; it does not copy their scaffolding:

- [sivaprasadreddy/spring-modular-monolith](https://github.com/sivaprasadreddy/spring-modular-monolith)
- [xsreality/spring-modulith-with-ddd](https://github.com/xsreality/spring-modulith-with-ddd)
- [Apache Fineract](https://github.com/apache/fineract)
