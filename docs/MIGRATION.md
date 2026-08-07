# Migration Guide

Use this guide to move the project to a personal computer without carrying machine-specific configuration. Decide first whether application records must move with the source code.

## Choice 1: Source Code Only

Use this option when local records are disposable. On the destination computer:

```sh
git clone https://github.com/32694/ledger-reconciliation-platform.git
cd ledger-reconciliation-platform
cp .env.example .env
chmod 600 .env
```

Replace both password placeholders in `.env`, install JDK 17 and PostgreSQL 17 or Docker Desktop, and follow the [User Guide](USER_GUIDE.md). Flyway creates a clean schema when the application first starts. No application records are transferred.

## Choice 2: Source Code and Database

First create a PostgreSQL custom-format dump on the source computer. Run this from the repository root after exporting `.env`:

```sh
mkdir -p ../migration-artifacts
PGPASSWORD="$DB_PASSWORD" pg_dump \
  -h localhost -p 5432 -U "$DB_USERNAME" -d ledger_platform \
  --format=custom --no-owner \
  --file=../migration-artifacts/ledger-platform.dump
```

Transfer `ledger-platform.dump` through a trusted encrypted channel. On the destination, create a new empty `ledger_platform` database owned by the newly created local role, then restore:

```sh
PGPASSWORD="$DB_PASSWORD" createdb \
  -h localhost -p 5432 -U "$DB_USERNAME" --owner="$DB_USERNAME" ledger_platform
PGPASSWORD="$DB_PASSWORD" pg_restore \
  -h localhost -p 5432 -U "$DB_USERNAME" -d ledger_platform \
  --no-owner --exit-on-error ledger-platform.dump
```

The destination database must be empty. If Docker Compose already initialized `ledger_platform`, drop and recreate only that confirmed local database before restoring; follow the destructive reset checks in the User Guide.

## Git Bundle Fallback

If GitHub is unavailable, create a bundle containing all local refs on the source computer from the repository root:

```sh
mkdir -p ../migration-artifacts
git bundle create ../migration-artifacts/ledger-reconciliation-platform.bundle --all
git bundle verify ../migration-artifacts/ledger-reconciliation-platform.bundle
```

Transfer the bundle to the destination computer and recover it:

```sh
git clone ledger-reconciliation-platform.bundle ledger-reconciliation-platform
cd ledger-reconciliation-platform
git remote set-url origin https://github.com/32694/ledger-reconciliation-platform.git
```

The remote command records the canonical GitHub location for later use; it does not require GitHub to be reachable during recovery.

## Deliberately Excluded

These machine-local or generated items are not part of source control or the Git bundle:

- `.env` and every local secret
- `target/` build output
- `.idea/`, `.vscode/`, and `*.iml` IDE metadata
- `data/`, `*.dump`, and `*.log`
- `.worktrees/` linked worktrees
- PostgreSQL or Docker volume data

Database dumps are ignored by Git and must be transferred separately when using Choice 2. Never migrate real customer data into this simulated project.

## Destination Secrets and Verification

Do not copy `.env` from the source computer. Create it again from `.env.example` and choose new database and administrator passwords on the destination. Ensure the local PostgreSQL role uses the new `DB_PASSWORD`.

After setup or restore:

```sh
git remote -v
git status --short
set -a
source .env
set +a
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test \
SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
./mvnw clean verify
./mvnw spring-boot:run
```

Confirm `git remote -v` shows `https://github.com/32694/ledger-reconciliation-platform.git`, the build succeeds, <http://localhost:8080/actuator/health> returns `UP`, login works with the new administrator secret, and expected records are present only when a database dump was restored.
