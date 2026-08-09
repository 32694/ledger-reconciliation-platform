# 迁移手册

本手册用于把 `ledger-reconciliation-platform` 和可选的 PostgreSQL 数据迁移到另一台电脑。项目使用 JDK 17、PostgreSQL 17 和 Flyway；不需要新增 Redis、Kafka 或其他外部服务。

## 迁移规则

- 应用启动时自动执行待执行的 Flyway migration。
- `V1-V8` 及后续 migration 已发布且不可修改；**不可直接修改已执行**的 Flyway migration。任何新结构必须新增更大的版本号。
- 本里程碑新增：
  - `V9__add_payment_refunds_and_reversals.sql`：为 payment instruction 增加反向支付关联字段、操作原因和反向类型约束。
  - `V10__create_audit_events.sql`：创建 `audit.audit_event` 表，保存 action/outcome 字段并添加时间查询索引。应用审计接口只提供追加能力；该迁移不包含禁止 `UPDATE` 或 `DELETE` 的数据库触发器。
  - `V11__allow_reverse_journal_types.sql`：允许账本 journal 使用 `REFUND` 和 `REVERSAL` 类型，不改变应用已有的借贷平衡校验。
  - `V12__add_reconciliation_operations.sql`：增加异步运行 attempt、差异负责人、解决方式和不可变案件时间线，并迁移已有解决记录。
  - `V13__allow_claimed_reconciliation_status.sql`：修复 V12 未替换完全的旧约束，使 `reconciliation_result.resolution_status` 合法接受 `CLAIMED`。
- Flyway 会在升级时按版本顺序执行；不要跳过或重排脚本，也不要手动修改 `flyway_schema_history`。

## 1. 迁移前准备和停机

1. 在源电脑停止 Java 进程（`Ctrl-C`），确认没有写入中的资金操作或对账任务；必须先完成升级前备份。
2. 确认源电脑和目标电脑均安装 JDK 17、PostgreSQL 17 客户端（`pg_dump`、`pg_restore`）。
3. 在仓库根目录检查 Git 工作区，只迁移已提交的代码：

```sh
git status --short
git log -1 --oneline
```

4. 不要复制 `.env`。目标电脑应从 `.env.example` 重新创建配置并使用新密码。

## 2. 备份 PostgreSQL 数据

数据库备份是可选的；只迁移代码时可跳到下一节。先在源电脑执行 `set -a; source .env; set +a`：

```sh
mkdir -p ../migration-artifacts
PGPASSWORD="$DB_PASSWORD" pg_dump \
  -h localhost -p 5432 -U "$DB_USERNAME" -d ledger_platform \
  --format=custom --no-owner \
  --exclude-table-data=identity.admin_user \
  --file=../migration-artifacts/ledger-platform.dump
```

备份不包含 `identity.admin_user` 行，因此管理员密码不会迁移；目标电脑首次启动会根据新的 `APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD` 初始化管理员。通过可信的加密渠道把 `ledger-platform.dump` 传到目标电脑，数据库 dump 不要提交到 Git。

## 3. 目标电脑安装和恢复

在目标电脑：

```sh
git clone https://github.com/32694/ledger-reconciliation-platform.git
cd ledger-reconciliation-platform
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，填入新的 `DB_*` 和 `APP_ADMIN_*` 值，然后导出：

```sh
set -a
source .env
set +a
```

按照[用户手册](USER_GUIDE.md)启动 PostgreSQL 17（Homebrew 或 Docker Compose 二选一），创建 `ledger_platform` 和 `ledger_platform_test`，并确认使用的是目标电脑的空数据库。若要恢复业务数据，先做只读确认：

```sh
PGPASSWORD="$DB_PASSWORD" psql -h localhost -p 5432 -U "$DB_USERNAME" -d postgres \
  -c "SELECT current_database(), inet_server_addr(), inet_server_port();" \
  -c "SELECT datname FROM pg_database WHERE datname IN ('ledger_platform', 'ledger_platform_test') ORDER BY datname;"
```

确认只操作这两个本地数据库后，仅重建主库 `ledger_platform`（保留测试库）：

```sh
PGPASSWORD="$DB_PASSWORD" dropdb -h localhost -p 5432 -U "$DB_USERNAME" ledger_platform
PGPASSWORD="$DB_PASSWORD" createdb -h localhost -p 5432 -U "$DB_USERNAME" \
  --owner="$DB_USERNAME" ledger_platform
PGPASSWORD="$DB_PASSWORD" pg_restore \
  -h localhost -p 5432 -U "$DB_USERNAME" -d ledger_platform \
  --no-owner --exit-on-error ../migration-artifacts/ledger-platform.dump
```

`dropdb` 是破坏性命令，只能在已确认的本地空目标上运行。若只迁移源码，不执行 `dropdb`/`pg_restore`，直接启动应用即可由 Flyway 创建全新 schema。

## 4. 升级顺序和停机窗口

迁移升级需要短暂停机：

1. 先完成备份并停止旧版本应用。
2. 更新代码到包含 V9-V13 的提交（`git pull --ff-only` 或重新 clone）。
3. 启动新版本应用：

```sh
./mvnw spring-boot:run
```

4. Flyway 按顺序执行尚未执行的 V9-V13；日志出现 migration 成功后再开放管理页面。V13 是 V12 的旧约束修复，两者不能跳过、重排或合并。
5. 登录并检查 `/admin/payments/top-up`、`/admin/payments/transfer`、`/admin/reconciliation`、`/admin/reconciliation/cases` 和 `/admin/audit`，确认近期记录、运行历史、案件时间线和审计事件可见。

不要在应用运行时手动执行迁移脚本，也不要让旧版本应用和新版本应用同时写同一个数据库。

异步对账 executor 是应用进程内本地线程池，不是持久化队列或独立 worker。应用重启后，原进程中的执行任务消失；新进程会把遗留的 `QUEUED`/`RUNNING` active run 标记为 `FAILED`，原任务不会续跑，需由管理员重新发起并创建新的 attempt。部署时不得假设多实例之间会转移或接管 active run。

## 5. 升级校验

在目标仓库根目录执行：

```sh
java -version
psql --version
git remote -v
git status --short
./mvnw clean verify
```

`java -version` 和 `psql --version` 应为 17；`./mvnw clean verify` 必须成功。应用启动后：

```sh
curl --fail http://localhost:8080/actuator/health
```

应返回包含 `"status":"UP"` 的响应。登录后检查：

- 近期资金操作可打开成功充值/转账的交易详情；
- 可对满足条件的原交易提交全额退款或全额冲正；
- 原交易和反向交易的 journal 均存在且不可变；
- `/admin/audit` 可按 action/outcome 筛选；
- `/admin/reconciliation` 可查看异步运行状态和历史 attempt；
- `/admin/reconciliation/cases` 可认领、取消认领和解决差异，并显示不可变时间线；
- 旧的账户、支付、对账数据仍存在（如果恢复了 dump）。

## 6. 回滚边界

Flyway 不提供自动回滚。V12 会新增和回填运营记录，V13 会替换旧约束；这两项数据库变更不可通过切回旧应用自动撤销。发现新版本问题时：

- 如果 V9-V13 尚未执行，只需停止应用并修复代码；不要修改已有 migration 文件。
- 如果 migration 已执行但业务代码有问题，优先修复代码并重新部署；schema 向前兼容时使用新的 migration 继续演进。
- 如果必须撤回 schema，先停止应用，使用升级前的 PostgreSQL dump 恢复到独立的空数据库，再启动与该 schema 兼容的旧版本。升级后产生的运行历史、案件时间线和审计记录不可回滚到备份中，恢复会永久丢弃这些记录以及同期其他业务写入，必须先确认数据丢失范围。
- 不要通过删除 `flyway_schema_history`、编辑 checksum 或手工删除 V9-V13 来“回滚”。

回滚后再次启动时，应用和数据库的 migration 版本必须匹配。恢复完成后重新运行 `./mvnw clean verify` 和健康检查。

## 7. Git Bundle 备用迁移

GitHub 不可用时，可在源电脑创建只含已提交 refs 的 bundle：

```sh
git status --short
git stash list
mkdir -p ../migration-artifacts
git bundle create ../migration-artifacts/ledger-reconciliation-platform.bundle --all
git bundle verify ../migration-artifacts/ledger-reconciliation-platform.bundle
```

确认工作区和 stash 均为空后，把 bundle 传到目标电脑：

```sh
git clone ledger-reconciliation-platform.bundle ledger-reconciliation-platform
cd ledger-reconciliation-platform
git remote set-url origin https://github.com/32694/ledger-reconciliation-platform.git
```

bundle 不包含未提交的工作树、`.env`、`target/`、数据库 volume 或 dump 文件；这些内容需按本手册单独处理。
