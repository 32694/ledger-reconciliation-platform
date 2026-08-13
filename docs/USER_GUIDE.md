# 用户手册

本手册说明如何在一台新电脑上运行模拟交易账本与自动对账平台。只使用合成数据。推荐使用 Docker Compose；如果 Docker Hub 无法拉取镜像，也提供 macOS Homebrew 本机运行方式。应用端口为 `8080`，数据库端口为 `5432`，RabbitMQ 端口为 `5672`，管理页面端口为 `15672`。

## 1. 本地启动

### 1.0 克隆项目并检查环境

推荐方式只要求 Git 和已启动的 Docker Desktop（包含 Compose 插件）：

```sh
git clone https://github.com/32694/ledger-reconciliation-platform.git
cd ledger-reconciliation-platform
git --version
docker info
docker compose version
```

`docker info` 必须同时显示 Client 和 Server 信息。若出现 daemon unavailable，先启动 Docker Desktop；若拉取镜像时出现 `auth.docker.io` 或 `registry-1.docker.io` timeout，这是 Docker 网络问题，不是项目账号、数据库密码或 `.env` 配置错误。可以稍后重试，或改用第 1.4 节的 Homebrew 方式。

### 1.1 准备配置

在仓库根目录执行：

```sh
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，设置 `DB_USERNAME`、`DB_PASSWORD`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`、`APP_ADMIN_USERNAME`、`APP_ADMIN_PASSWORD` 和 `APP_READ_API_KEY`。这些值都是为本地项目自行设置的，不是 Docker Hub 账号密码。`APP_ADMIN_*` 用于登录 Java 管理页面，`APP_READ_API_KEY` 用于 Agent 调用只读 API，三类凭据用途不同。

可以保留示例用户名，并把每个 `change-this-*` 替换为自己选定的本地值：

```dotenv
DB_USERNAME=ledger_app
DB_PASSWORD='your-local-database-password'
RABBITMQ_USERNAME=ledger_app
RABBITMQ_PASSWORD='your-local-rabbitmq-password'
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD='your-local-admin-password'
APP_READ_API_KEY='your-local-read-api-key'
```

值必须是单行内容。若包含 shell 特殊字符，用单引号包住，且值本身不要包含单引号。`.env` 已被 Git 忽略；不要提交它，也不要把真实密码或 API Key 粘贴到 issue、截图或聊天中。

### 1.2 三服务一键启动（推荐）

```sh
docker compose up --build -d
docker compose ps
```

等待 `app`、`db`、`rabbitmq` 均显示 `healthy`。登录地址为 <http://localhost:8080/login>，RabbitMQ 管理页面为 <http://localhost:15672>。两处分别使用 `.env` 中的管理员账号和 RabbitMQ 账号。Compose 自动读取仓库根目录的 `.env`，不需要手工导出。

再执行健康检查：

```sh
curl --fail http://localhost:8080/actuator/health
```

返回包含 `"status":"UP"` 即表示应用、PostgreSQL 和 RabbitMQ 已连通。首次构建可能需要数分钟；终端回到提示符且 `docker compose ps` 显示 healthy 后才算启动完成。

停止服务但保留 PostgreSQL 和 RabbitMQ named volume：

```sh
docker compose down
```

### 1.3 宿主机运行 Java

只启动依赖：

```sh
docker compose up -d db rabbitmq
```

应用不会自动加载 `.env`，宿主机模式下每个新终端都要执行：

```sh
set -a
. ./.env
set +a
```

如需测试数据库，在依赖启动后创建：

```sh
docker compose exec db psql -U "$DB_USERNAME" -d ledger_platform \
  -c 'CREATE DATABASE ledger_platform_test;'
```

若使用已有实例，按所在环境的管理方式创建 `.env` 指向的主库和 `ledger_platform_test` 测试库，并确保 RabbitMQ 用户有 `/` vhost 的读写配置权限；不要把本机路径、用户名或凭据写入文档或提交。

### 1.4 macOS 完全本机运行（Docker Hub 不可用时）

此方式不使用 Docker 镜像。先安装并启动依赖：

```sh
brew install openjdk@17 postgresql@17 rabbitmq
brew services start postgresql@17
brew services start rabbitmq
```

Apple Silicon Mac 在当前终端设置工具路径；Intel Mac 将 `/opt/homebrew` 改为 `/usr/local`：

```sh
export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/opt/postgresql@17/bin:/opt/homebrew/opt/rabbitmq/sbin:$PATH"
java -version
psql --version
rabbitmq-diagnostics -q ping
```

`java` 和 `psql` 应显示 17，RabbitMQ 应返回 `Ping succeeded`。按第 1.1 节创建 `.env`，导出配置，并在本机 PostgreSQL 中创建角色和两个数据库：

```sh
set -a
. ./.env
set +a

psql postgres --set=db_user="$DB_USERNAME" --set=db_password="$DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'db_user') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', 'ledger_platform', :'db_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ledger_platform') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', 'ledger_platform_test', :'db_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ledger_platform_test') \gexec
SQL
```

在 RabbitMQ 中创建应用用户并授予默认 vhost 权限（用户已存在时跳过 `add_user`）：

```sh
rabbitmqctl add_user "$RABBITMQ_USERNAME" "$RABBITMQ_PASSWORD"
rabbitmqctl set_permissions -p / "$RABBITMQ_USERNAME" '.*' '.*' '.*'
rabbitmqctl set_user_tags "$RABBITMQ_USERNAME" administrator
```

最后在同一终端启动应用：

```sh
./mvnw spring-boot:run
```

打开 <http://localhost:8080/login>。停止 Java 按 `Ctrl-C`；停止后台依赖使用 `brew services stop rabbitmq` 和 `brew services stop postgresql@17`。不要同时启动 Compose 和 Homebrew 的 PostgreSQL/RabbitMQ，否则端口会冲突。

### 1.5 测试、校验和启动

使用本地数据库凭据运行测试：

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test \
SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
./mvnw test
```

完整验收（编译、测试、打包）：

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test \
SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
./mvnw clean verify
./mvnw -Pmessaging-integration verify
```

启动应用：

```sh
./mvnw spring-boot:run
```

也可以运行打包后的 JAR：

```sh
java -jar target/ledger-reconciliation-platform-0.1.0-SNAPSHOT.jar
```

Flyway 会在应用启动时自动执行待执行迁移。启动后打开 <http://localhost:8080/login>，使用 `.env` 中的 `APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD` 登录。可用 `curl --fail http://localhost:8080/actuator/health` 检查 PostgreSQL 和 RabbitMQ 均可用。

### 1.6 只读集成 API

只读 API 用于给 Agent 或其他运营工具读取已落库证据。它们只使用 `GET`，不会创建充值、转账、解决案件或修改账本。先在 `.env` 设置 `APP_READ_API_KEY`，然后重新启动应用；Compose 模式执行 `docker compose up -d --force-recreate app`，宿主机模式重启 `./mvnw spring-boot:run`。

```sh
set -a
. ./.env
set +a

curl -sS \
  -H "X-Read-Api-Key: $APP_READ_API_KEY" \
  http://localhost:8080/api/v1/reconciliation/cases | python3 -m json.tool
```

从案件列表中取出 Java 返回的 `id`（UUID）后，可以读取完整证据包：

```sh
curl -sS \
  -H "X-Read-Api-Key: $APP_READ_API_KEY" \
  "http://localhost:8080/api/v1/reconciliation/results/<resultId>/evidence" | python3 -m json.tool
```

把 `<resultId>` 替换为案件列表中真实的 `id` UUID。若列表为空，先按第 2 节创建充值，再按第 6 节导入账单并运行对账。

可用的只读路径还有 `/payments/<paymentId>`、`/ledger/transactions/<businessReference>` 和 `/audit/<aggregateId>`。未提供或不匹配 API Key 时，接口返回 HTTP `401`。Agent 当前演示数据中的 `CASE-1001` 是它自己的外部编号，不是 Java 接口的 UUID；真正联调时应先建立案件编号到 UUID 的映射，不能直接拼接到 `{resultId}`。

## 2. 账户、充值和转账

1. 进入**客户账户**（`/admin/accounts`），点击**新建账户**，输入合成的持有人名称并提交。
2. 进入**账户充值**（`/admin/payments/top-up`），选择收款账户，输入正整数金额（单位为 CNY cents）和唯一幂等键，点击**提交充值**。
3. 进入**账户转账**（`/admin/payments/transfer`），选择不同的付款账户和收款账户，输入正整数金额及新的幂等键，点击**提交转账**。
4. 回到**客户账户**检查余额，进入**账本流水**（`/admin/ledger`）检查最近的 journal。

每笔成功转账写入一个平衡 journal：一条 `DEBIT` 减少付款账户，一条 `CREDIT` 增加收款账户，金额相同。PostgreSQL 行锁会串行化同一付款账户的并发扣款，因此**不能出现负余额**。同一幂等键和完全相同的请求会返回原结果，不会重复扣款；修改金额、账户或操作类型后复用该键会被拒绝。

手工冒烟检查：给付款账户充值 `10,000` cents，再向另一个账户转账 `2,500` cents。余额应分别显示人民币 75.00 和人民币 25.00，账本中有一条 `TRANSFER`。超过余额发起新转账时，页面显示**付款账户余额不足**，数据库记录为 `FAILED`，失败码为 `INSUFFICIENT_FUNDS`，且不会新增 journal。

## 3. 全额退款和全额冲正

反向操作只允许对 `SUCCEEDED` 的原始 `TOP_UP` 或 `TRANSFER` 执行，且只能全额操作一次。

1. 在经营概览或资金操作页的**近期资金操作**表中，打开一笔成功充值或成功转账的业务流水号，进入交易详情（`/admin/payments/{paymentId}`）。
2. 在详情页点击**发起全额退款**（原交易为充值）或**发起全额冲正**（原交易为转账）。
3. 在表单填写必填的操作原因（最多 500 个字符）和唯一幂等键（最多 128 个字符），提交对应命令。
4. 成功后，详情页显示原交易和反向交易的互相链接；原支付记录和原 journal 不变，账本中新增一份精确相反的不可变 journal。因此可检查到原交易与反向交易链接，以及**两份不可变 journal**。

反向交易分别使用英文底层类型 `REFUND` 和 `REVERSAL`，页面显示中文的**全额退款**或**全额冲正**。已存在进行中或成功的反向交易时，重复提交只返回同一反向交易，不会生成第二笔。

## 4. 失败重试

反向操作也会记录 `FAILED` 支付指令和审计事件。若详情页提示可退回余额不足，底层失败码保留为 `INSUFFICIENT_FUNDS`：

1. 先在**账户充值**页给反向操作需要扣款的源钱包补足余额。
2. 返回原交易详情，重新发起相同的全额退款或全额冲正。
3. 填写新的唯一幂等键，点击提交；失败指令的原幂等键不能复用，必须**使用新幂等键重试**。

如果幂等键已被不同请求使用，页面会提示更换幂等键；如果原交易不是成功的充值/转账，详情页会返回 `409` 并提示该交易不可退款或冲正。

## 5. 审计日志

进入**审计日志**（`/admin/audit`）。页面默认显示最近 100 条管理员业务事件，可按 `action`（操作类型）和 `outcome`（`SUCCEEDED` 或 `FAILED`）筛选，再点击**查询**；点击**清除筛选**恢复全部记录。

审计事件覆盖账户创建、充值、转账、退款、冲正、对账导入、执行对账和处理差异。事件包含时间、操作人、业务对象、结果、摘要和关联标识。审计事件通过应用接口只追加，后台不提供修改或删除入口；数据库层未禁止直接修改该表，因此不要把数据库管理员权限授予普通业务用户。重复提交同一幂等请求不会额外写入重复事件。

## 6. 异步对账和异常处理

### 6.1 管理对账规则

1. 进入**对账规则**（`/admin/reconciliation/rules`），选择**默认规则**或某个**渠道规则**。
2. 填写金额容差（cents）和查询窗口（小时），点击**保存草稿**。
3. 核对草稿后点击**发布**。导入只使用已发布版本；保存草稿不会改变任何已导入批次。

### 6.2 导入并运行对账

在左侧导航进入**自动对账**（`/admin/reconciliation`），点击**导入渠道账单**，选择任一已启用渠道（例如支付宝、微信支付或银联）的 UTF-8 CSV，再点击**导入账单**。固定表头为：

```text
channel_transaction_id,amount_cents,occurred_at
```

每行必须包含非空渠道交易号、正整数金额（CNY cents）和 ISO-8601 时间。文件上限为 `20 MB`，数据行上限为 `100,000`。任意一行无效时，整个批次标记为 `IMPORT_FAILED`，不保存明细。

导入前先选择已启用渠道。系统按该渠道的已发布规则版本；没有渠道覆盖规则时使用默认规则。规则版本发布后不可变，导入批次会锁定版本、金额容差和查询窗口，后续发布新版本不会改变历史批次。导入成功后自动进入批次详情，可直接点击**开始对账**；需要查看历史批次时，从**自动对账**列表打开对应详情。任务使用 Spring Batch 分块执行，每 500 行提交一个检查点。运行状态的中文含义如下：

- `QUEUED`：等待执行，任务已记录并等待 Batch 调度。
- `RUNNING`：对账中，当前 attempt 正在计算和保存结果。
- `SUCCEEDED`：已完成，本次 attempt 已保存匹配数和差异数。
- `FAILED`：执行失败，本次 attempt 已结束并保留失败原因。

`QUEUED` 或 `RUNNING` 时，批次详情使用 HTMX 每 `2` 秒刷新一次状态；进入终态后停止刷新。失败后有两种明确选择：**从检查点继续**会重启同一次运行并保留已提交的工作结果；**新建尝试**会创建下一次 attempt，重新处理该批次。运行历史按“第 N 次”倒序保留，失败历史不会被覆盖。

Batch 元数据位于 Flyway 管理的 `batch` schema。应用启动时会恢复仍可重启的同一运行；已完成检查点不会重复写入。每个数据库同时只能运行一个应用实例；多副本必须由外部 leader election、lease 和 heartbeat 保证唯一调度者并检测失联，应用自身不实现这些协调机制。

系统只把成功充值作为渠道候选，结果类型如下：

| 页面显示 | Internal status | 含义 |
| --- | --- | --- |
| 匹配一致 | `MATCHED` | 渠道交易号和金额均匹配。 |
| 金额不一致 | `AMOUNT_MISMATCH` | 渠道交易号存在但金额不同。 |
| 仅渠道存在 | `CHANNEL_ONLY` | 渠道有记录，内部没有成功充值。 |
| 仅内部存在 | `INTERNAL_ONLY` | 内部有成功充值，渠道没有记录。 |

相同文件的 SHA-256 digest 具备导入幂等性，重复上传返回原批次。

### 6.3 在异常工作台处理差异

在左侧导航进入**异常工作台**（`/admin/reconciliation/cases`）。默认展示待处理和处理中的差异；可以按结果、处理状态、负责人或**仅看我的**筛选。点击案件的**查看详情**后，状态按 `OPEN` -> `CLAIMED` -> `RESOLVED` 流转：

1. `OPEN`（待处理）时点击**认领案件**，案件变为 `CLAIMED`（处理中），当前管理员成为负责人。
2. 负责人可以点击**取消认领**，案件回到 `OPEN`；其他管理员不能代替负责人取消认领或解决。
3. 负责人选择解决方式、填写必填的解决备注，再点击**解决案件**，案件变为 `RESOLVED`（已解决）。

四种 `ResolutionCode` 只表达运营判断：

- `INTERNAL_CONFIRMED`：内部账务为准。
- `CHANNEL_CONFIRMED`：渠道账单为准。
- `IGNORED_TEST_DATA`：忽略测试数据。
- `OTHER`：其他。

每次认领、取消认领和解决都会追加到案件的**不可变时间线**，并写入**审计日志**（`/admin/audit`）；历史事件不能更新或删除。解决差异只记录运营结论；原支付和账本事实不会被修改，系统不会自动修改账本或渠道账单事实。已匹配结果无需处理，也不能进入案件流程。

### 6.4 演示流程

1. 新建一个 CSV，写入表头和一行不存在于近期充值记录的渠道号，例如 `DEMO-CHANNEL-ONLY,1000,2026-08-09T12:00:00Z`。
2. 按“**自动对账** -> **导入渠道账单** -> **导入账单**”操作；导入成功后自动进入批次详情，直接点击**开始对账**。观察状态从等待执行、对账中进入已完成。任务很快时，中间状态可能来不及在页面显示，但运行历史仍保留本次 attempt。
3. 在结果中确认该行显示**仅渠道存在**，再进入“**异常工作台** -> **查看详情**”。
4. 依次点击**认领案件**，选择一种解决方式并填写演示备注，再点击**解决案件**。
5. 检查案件处理记录和**审计日志**；两处应保留操作事实，而账本流水不会因该解决动作新增或改写 journal。

### 6.5 100,000 行演示

使用固定数据生成器创建 CSV；它只使用 POSIX `sh` 和 `awk`，先写入临时文件再原子移动到目标路径：

```sh
scripts/generate-reconciliation-demo.sh ./reconciliation-demo.csv 100000
```

导入该文件后，在批次详情观察总数、当前步骤和已处理进度。需要运行完整的 opt-in 性能验证时，先按本手册导出数据库环境变量，再执行：

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test \
SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
./mvnw -Preconciliation-performance -Dit.test=ReconciliationPerformanceIT verify
```

该验证会输出 `elapsedMs`、`channelRowsPerSecond`、`channelRows`、`resultRows` 和 `restartCount`。它只校验正确性和恢复语义，不设固定耗时阈值。

## 7. 可靠消息与站内通知

支付成功和对账完成时，业务事务会在同一个 PostgreSQL 事务中写入 Transactional Outbox。后台 Publisher 使用行锁领取待发送事件，投递到 RabbitMQ，并在收到 publisher confirm 后把事件标记为已投递。消息采用 **at-least-once** 语义，消费者以 `eventId` 做幂等消费，因此重复投递不会创建重复通知。

RabbitMQ 负责业务事件分发和站内通知，不会替代 Spring Batch；批量对账、检查点和失败恢复仍由 Spring Batch 负责。

### 7.1 验证正常通知

1. 按第 2 节完成一笔充值或转账，或按第 6 节完成一个对账批次。
2. 打开**站内通知**（`/admin/notifications`），应看到“资金操作成功”或“对账完成”。未读通知可点击**标记已读**。
3. 打开**消息运维**（`/admin/messaging`），检查 Outbox 的待投递、投递中、已投递、失败事件数量，以及 RabbitMQ 主队列和死信队列深度。

### 7.2 演示 Broker 中断与 Outbox 恢复

在应用保持运行时停止 Broker：

```sh
docker compose stop rabbitmq
```

此时提交一笔模拟支付，业务数据和 Outbox 事件仍在同一数据库事务中保存，但发布会按退避策略重试。恢复 Broker：

```sh
docker compose start rabbitmq
```

短暂中断时，事件最终变为**已投递**并生成一条通知。如果事件已耗尽 10 次发布尝试而变为**投递失败**，先确认 Broker 已恢复，再到 `/admin/messaging` 点击**重新投递**。不要直接修改 Outbox 表。

### 7.3 演示三次消费重试和 DLQ

下面的命令向支付事件路由键发送缺少必填字段的 JSON。管理 API 的 `%2F` 表示 RabbitMQ 默认 vhost `/`：

```sh
curl --fail -u "$RABBITMQ_USERNAME:$RABBITMQ_PASSWORD" \
  -H 'content-type: application/json' \
  -X POST http://localhost:15672/api/exchanges/%2F/ledger.events/publish \
  -d '{"properties":{"content_type":"application/json","delivery_mode":2},"routing_key":"payment.succeeded.v1","payload":"{}","payload_encoding":"string"}'
```

Listener 会总共尝试 3 次，退避约 1 秒和 2 秒，然后拒绝消息并由 RabbitMQ 路由到 `notification.events.v1.dlq`。等待约 4 秒后刷新 `/admin/messaging`，死信队列深度应增加；也可在 <http://localhost:15672> 查看队列。该消息不会创建通知或消费去重记录。

## 8. 停止和清理

停止宿主机 Java 进程：`Ctrl-C`。停止 Compose 三个服务：

```sh
docker compose down
```

`docker compose down` 不带 `--volumes` 时会保留 named volume。删除本地数据前，先确认目标只包括本项目的 PostgreSQL 和 RabbitMQ volume；删除数据库或 volume 是破坏性操作，不要对共享或生产环境执行。

## 9. 常见错误

- **Docker 拉取镜像超时**：`auth.docker.io`、`registry-1.docker.io` 的 `context deadline exceeded` 表示 Docker 到 Docker Hub 的网络超时，和 `.env` 密码无关。检查网络或代理后重试，或使用第 1.4 节的 Homebrew 方式。
- **Connection refused**：确认 PostgreSQL 17 正在运行、`5432` 未被其他实例占用，并检查 `DB_URL`。
- **RabbitMQ connection refused**：确认 RabbitMQ 4 正在运行、`5672` 可访问，并检查 `RABBITMQ_HOST`、用户名和密码。
- **消息运维页显示队列不可用**：应用仍可从 PostgreSQL 读取 Outbox；恢复 RabbitMQ 后刷新页面，Publisher 会继续重试待投递事件。
- **Password authentication failed**：确认数据库角色密码和 `DB_PASSWORD` 相同；旧 Compose volume 会保留首次初始化的密码。
- **Database does not exist**：创建 `ledger_platform` 和 `ledger_platform_test`，再重新运行测试或应用。
- **`app.admin.username is required` / `app.admin.password is required`**：在启动应用的同一终端执行 `set -a; . ./.env; set +a`。
- **付款账户余额不足**：这是业务保护，不会写入转账 journal；补足余额后用新幂等键提交。
- **反向操作 `INSUFFICIENT_FUNDS`**：补足源钱包后，用新幂等键重试全额退款或全额冲正。
- **幂等键冲突**：同一 key 的请求参数不能变化；更换唯一 key 后再提交。
- **端口已占用**：确认本机服务与 Docker Compose 没有同时使用 `5432`、`5672`、`8080` 或 `15672`，停止冲突实例后重试。
- **只读 API 返回 `401`**：确认请求头是 `X-Read-Api-Key`，且值与 Java 启动时读取的 `APP_READ_API_KEY` 完全一致；修改 `.env` 后必须重启 Java 应用。
