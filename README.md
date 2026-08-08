# Ledger Reconciliation Platform

一个用于学习和演示的 Java 交易账本与自动对账平台。系统只处理模拟资金和合成数据，不用于真实资金、客户数据或受监管生产场景。

## 当前能力

这是一个 Spring Boot 模块化单体，包含以下模块：

- `identity`：管理员表单登录和启动时初始化管理员。
- `accounts`：创建、查询模拟客户账户，余额由账本分录计算。
- `ledger`：保存平衡且不可变的 journal，展示近期账本流水。
- `payments`：幂等充值、账户转账，以及成功充值的全额退款、成功转账的全额冲正。
- `reconciliation`：导入合成渠道账单，执行精确匹配对账，记录差异处理。
- `audit`：记录管理员业务动作和结果，提供按 action/outcome 筛选的审计日志页面。

转账采用**双重记账**：一条 `DEBIT` 和一条 `CREDIT` 必须同时入账。PostgreSQL 行锁保证并发扣款按账户串行执行。

管理页面入口：

- `/admin`：经营概览和近期资金操作。
- `/admin/accounts`：客户账户。
- `/admin/payments/top-up`：账户充值。
- `/admin/payments/transfer`：账户转账。
- `/admin/ledger`：账本流水。
- `/admin/reconciliation`：自动对账。
- `/admin/audit`：审计日志。

近期资金操作表中的每条记录都可以打开交易详情。成功的充值可以提交**全额退款**，成功的转账可以提交**全额冲正**；操作要求填写原因和唯一幂等键。反向操作成功后，原交易保持不变，详情页互相显示原交易和反向交易链接，账本中保留两份不可变 journal。失败反向操作会保留底层失败码（例如 `INSUFFICIENT_FUNDS`），补足源钱包后必须使用新幂等键重试。

自动对账支持固定 CSV 格式，详见[用户手册](docs/USER_GUIDE.md)。管理员业务动作会写入只追加、不可修改的审计事件，可在审计日志页按 action 和 outcome 筛选。

## 技术栈

- JDK 17、Spring Boot 3.5、Spring Modulith
- Spring Data JPA、Hibernate、PostgreSQL 17、Flyway
- Thymeleaf、HTMX、Spring Security、响应式管理页面
- JUnit 5、AssertJ、MockMvc、Spring Modulith Test、ArchUnit
- Maven Wrapper、Docker Compose、GitHub Actions

## 前置条件

- macOS 或其他 Unix-like 系统
- JDK 17
- PostgreSQL 17（Homebrew）或 Docker Desktop（Docker Compose）

## 快速启动

```sh
cp .env.example .env
# 修改 .env 中的数据库和管理员密码；密码使用单行单引号值，且不要包含单引号。
set -a
source .env
set +a
docker compose up -d
./mvnw spring-boot:run
```

打开 <http://localhost:8080/login>，使用 `APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD` 登录。应用不会自动读取 `.env`，每个新终端都需要重新执行 `source`。数据库启动后，Flyway 会在应用启动阶段自动执行待执行迁移。

完整的本地启动、业务操作、失败重试和常见错误处理见[用户手册](docs/USER_GUIDE.md)；迁移到另一台电脑或迁移 PostgreSQL 数据见[迁移手册](docs/MIGRATION.md)。

## 验证

准备好 `ledger_platform_test` 后运行：

```sh
./mvnw clean verify
```

该命令会编译、执行测试并生成可运行的 JAR：`target/ledger-reconciliation-platform-0.1.0-SNAPSHOT.jar`。
