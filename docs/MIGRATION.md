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

Both the source and destination need PostgreSQL 17 command-line client tools. On each macOS computer, install the same PostgreSQL 17 package used in the User Guide, add its tools to the current shell, and confirm both utilities report version 17:

```sh
brew install postgresql@17
export PATH="$(brew --prefix postgresql@17)/bin:$PATH"
pg_dump --version
pg_restore --version
```

When PostgreSQL runs in Docker Compose, do not start the Homebrew PostgreSQL service because both would use port 5432. The host `pg_dump` and `pg_restore` commands below connect to the container through its published `localhost:5432` port.

On the source computer, create a PostgreSQL custom-format dump from the repository root after exporting `.env`:

```sh
mkdir -p ../migration-artifacts
PGPASSWORD="$DB_PASSWORD" pg_dump \
  -h localhost -p 5432 -U "$DB_USERNAME" -d ledger_platform \
  --format=custom --no-owner \
  --file=../migration-artifacts/ledger-platform.dump
```

On the destination computer, complete the source-code setup with new local secrets:

```sh
git clone https://github.com/32694/ledger-reconciliation-platform.git
cd ledger-reconciliation-platform
cp .env.example .env
chmod 600 .env
```

Replace both password placeholders in `.env`; do not copy secrets from the source computer. Then export the destination values:

```sh
set -a
source .env
set +a
```

From the destination repository root, create `../migration-artifacts` and transfer the dump through a trusted encrypted channel to `../migration-artifacts/ledger-platform.dump`.

Before restoring, complete either the Homebrew PostgreSQL or Docker Compose setup in the [User Guide](USER_GUIDE.md). Do not skip these steps: create the destination-only `ledger_app` role with the new `DB_PASSWORD`, start PostgreSQL 17, and create both `ledger_platform` and `ledger_platform_test`. Keep `ledger_platform_test`; the verification command needs it.

The restore target must be an empty `ledger_platform`. The database setup above creates that database, so confirm the local server and the two exact database names before rebuilding only `ledger_platform`.

For Homebrew PostgreSQL:

```sh
PGPASSWORD="$DB_PASSWORD" psql -h localhost -p 5432 -U "$DB_USERNAME" -d postgres \
  -c "SELECT current_database(), inet_server_addr(), inet_server_port();" \
  -c "SELECT datname FROM pg_database WHERE datname IN ('ledger_platform', 'ledger_platform_test') ORDER BY datname;"
PGPASSWORD="$DB_PASSWORD" dropdb -h localhost -p 5432 -U "$DB_USERNAME" ledger_platform
PGPASSWORD="$DB_PASSWORD" createdb -h localhost -p 5432 -U "$DB_USERNAME" \
  --owner="$DB_USERNAME" ledger_platform
```

For Docker Compose:

```sh
docker compose ps
docker compose exec db psql -U "$DB_USERNAME" -d postgres \
  -c "SELECT current_database(), inet_server_addr(), inet_server_port();" \
  -c "SELECT datname FROM pg_database WHERE datname IN ('ledger_platform', 'ledger_platform_test') ORDER BY datname;"
docker compose exec db dropdb -U "$DB_USERNAME" ledger_platform
docker compose exec db createdb -U "$DB_USERNAME" --owner="$DB_USERNAME" ledger_platform
```

The `dropdb` command is destructive. Run one option only after its read-only checks identify your local PostgreSQL instance and show exactly `ledger_platform` and `ledger_platform_test`. The commands remove only `ledger_platform`; they deliberately retain `ledger_platform_test`.

Restore the transferred dump into the newly empty primary database:

```sh
PGPASSWORD="$DB_PASSWORD" pg_restore \
  -h localhost -p 5432 -U "$DB_USERNAME" -d ledger_platform \
  --no-owner --exit-on-error ../migration-artifacts/ledger-platform.dump
```

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
