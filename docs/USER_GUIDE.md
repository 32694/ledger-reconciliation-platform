# 用户手册

本手册说明如何运行模拟交易账本与自动对账平台。只使用合成数据。运行环境为 Git、JDK 17、Maven Wrapper 和 PostgreSQL 17，应用端口为 `8080`，数据库端口为 `5432`。

## 1. 本地启动

### 1.1 准备配置

在仓库根目录执行：

```sh
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，设置 `DB_USERNAME`、`DB_PASSWORD`、`APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD`。密码使用单行单引号值，且不要包含单引号。应用不会自动加载 `.env`，每个新终端都要执行：

```sh
set -a
source .env
set +a
```

### 1.2 启动 PostgreSQL 17

使用已有的 PostgreSQL 17 实例，或使用仓库提供的 Docker Compose：

```sh
docker compose up -d
docker compose ps
docker compose exec db psql -U "$DB_USERNAME" -d ledger_platform \
  -c 'CREATE DATABASE ledger_platform_test;'
```

等待 `db` 显示 `healthy`。Compose 只启动 PostgreSQL，Java 应用仍在宿主机运行。若使用已有实例，按所在环境的管理方式创建 `.env` 指向的主库和 `ledger_platform_test` 测试库；不要把本机路径、用户名或凭据写入文档或提交。

### 1.3 测试、校验和启动

使用本地数据库凭据运行测试：

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test \
SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
./mvnw test
```

完整验收（编译、测试、打包）：

```sh
./mvnw clean verify
```

启动应用：

```sh
./mvnw spring-boot:run
```

也可以运行打包后的 JAR：

```sh
java -jar target/ledger-reconciliation-platform-0.1.0-SNAPSHOT.jar
```

Flyway 会在应用启动时自动执行待执行迁移。启动后打开 <http://localhost:8080/login>，使用 `.env` 中的 `APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD` 登录。可用 `curl --fail http://localhost:8080/actuator/health` 检查健康状态。

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

### 6.1 导入并运行对账

在左侧导航进入**自动对账**（`/admin/reconciliation`），点击**导入渠道账单**，选择一个 `SYNTHETIC_CHANNEL` 的 UTF-8 CSV，再点击**导入账单**。固定表头为：

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

Batch 元数据位于 Flyway 管理的 `batch` schema。应用启动时会恢复仍可重启的同一运行；已完成检查点不会重复写入。每个数据库同时只能运行一个应用实例；部署多副本时必须由外部 leader 或 lease 保证只有一个实例调度该数据库的对账任务。

系统只把成功充值作为渠道候选，结果类型如下：

| 页面显示 | Internal status | 含义 |
| --- | --- | --- |
| 匹配一致 | `MATCHED` | 渠道交易号和金额均匹配。 |
| 金额不一致 | `AMOUNT_MISMATCH` | 渠道交易号存在但金额不同。 |
| 仅渠道存在 | `CHANNEL_ONLY` | 渠道有记录，内部没有成功充值。 |
| 仅内部存在 | `INTERNAL_ONLY` | 内部有成功充值，渠道没有记录。 |

相同文件的 SHA-256 digest 具备导入幂等性，重复上传返回原批次。

### 6.2 在异常工作台处理差异

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

### 6.3 演示流程

1. 新建一个 CSV，写入表头和一行不存在于近期充值记录的渠道号，例如 `DEMO-CHANNEL-ONLY,1000,2026-08-09T12:00:00Z`。
2. 按“**自动对账** -> **导入渠道账单** -> **导入账单**”操作；导入成功后自动进入批次详情，直接点击**开始对账**。观察状态从等待执行、对账中进入已完成。任务很快时，中间状态可能来不及在页面显示，但运行历史仍保留本次 attempt。
3. 在结果中确认该行显示**仅渠道存在**，再进入“**异常工作台** -> **查看详情**”。
4. 依次点击**认领案件**，选择一种解决方式并填写演示备注，再点击**解决案件**。
5. 检查案件处理记录和**审计日志**；两处应保留操作事实，而账本流水不会因该解决动作新增或改写 journal。

### 6.4 100,000 行演示

使用固定数据生成器创建 CSV；它只使用 POSIX `sh` 和 `awk`，先写入临时文件再原子移动到目标路径：

```sh
scripts/generate-reconciliation-demo.sh ./reconciliation-demo.csv 100000
```

导入该文件后，在批次详情观察总数、当前步骤和已处理进度。需要运行完整的 opt-in 性能验证时，先按本手册导出数据库环境变量，再执行：

```sh
./mvnw -Preconciliation-performance -Dit.test=ReconciliationPerformanceIT verify
```

该验证会输出 elapsed、throughput、total 和 restartCount。它只校验正确性和恢复语义，不设固定耗时阈值。

## 7. 停止和清理

停止 Java 进程：`Ctrl-C`。停止 Docker Compose 数据库：

```sh
docker compose stop
```

`docker compose stop` 会保留 named volume。删除本地数据前，先确认目标只包括 `ledger_platform` 和 `ledger_platform_test`；删除数据库和 volume 是破坏性操作，不要对共享或生产数据库执行。

## 8. 常见错误

- **Connection refused**：确认 PostgreSQL 17 正在运行、`5432` 未被其他实例占用，并检查 `DB_URL`。
- **Password authentication failed**：确认数据库角色密码和 `DB_PASSWORD` 相同；旧 Compose volume 会保留首次初始化的密码。
- **Database does not exist**：创建 `ledger_platform` 和 `ledger_platform_test`，再重新运行测试或应用。
- **`app.admin.username is required` / `app.admin.password is required`**：在启动应用的同一终端执行 `set -a; source .env; set +a`。
- **付款账户余额不足**：这是业务保护，不会写入转账 journal；补足余额后用新幂等键提交。
- **反向操作 `INSUFFICIENT_FUNDS`**：补足源钱包后，用新幂等键重试全额退款或全额冲正。
- **幂等键冲突**：同一 key 的请求参数不能变化；更换唯一 key 后再提交。
- **端口已占用**：确认 PostgreSQL 实例与 Docker Compose 没有同时使用同一端口，再停止冲突实例后重试。
