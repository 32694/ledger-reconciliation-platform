# 可配置对账规则与 Spring Batch 设计

日期：2026-08-10

## 1. 背景

系统已经支持渠道 CSV 导入、按渠道流水号精确对账、异步运行尝试、失败重试和异常案件闭环。当前匹配逻辑仍一次性把账单和内部交易读入内存，并由本地 `TaskExecutor` 执行；金额必须完全相等，规则也无法按渠道配置。这种实现适合小规模功能验证，但无法稳定演示规则版本审计、分块处理和进程中断后的续跑能力。

本阶段在现有 Spring Boot 3.5、Java 17、PostgreSQL 17 模块化单体内增加可配置规则，并用 Spring Batch 接管对账执行。改造保留现有账单导入、运行历史、异常案件和中文管理页面，不拆微服务，也不引入消息队列。

## 2. 已确认决策

1. 渠道流水号必须完全一致，不使用金额和时间窗口兜底匹配。
2. 规则支持平台默认规则和渠道覆盖规则；渠道未发布规则时回退到默认规则。
3. 规则先保存草稿，再发布为不可变版本。批次创建时锁定规则版本，后续发布不改变历史批次。
4. 金额容差使用固定金额，不支持百分比或组合表达式。
5. Spring Batch 只接管对账执行，账单 CSV 导入保持现状。
6. 单批次演示目标为 10 万笔。
7. 上传账单时从系统渠道列表选择渠道，首期预置支付宝、微信支付和银联。
8. 页面展示使用中文，代码、表名、字段、枚举和日志使用英文。

## 3. 目标

1. 管理员可以维护默认规则及各渠道规则的草稿、发布版本和历史版本。
2. 每个账单批次明确关联渠道和创建时有效的规则版本。
3. 对账 Job 使用确定顺序和固定 chunk 分块执行，默认 chunk size 为 500。
4. 失败作业可从最后提交的 checkpoint 继续，已提交结果不重复。
5. 页面展示 Job 状态、处理进度、结果汇总、重启次数和失败原因。
6. 普通个人电脑可以完成 10 万笔演示，并验证中断续跑和结果幂等。
7. 项目迁移后只依赖 JDK 17、PostgreSQL 17、Git 和 Maven Wrapper。

## 4. 非目标

- 不拆分微服务，不引入 Kafka、RabbitMQ 或外部调度平台。
- 不实现渠道 API 拉取、定时账单下载或对象存储。
- 不支持模糊流水号、金额加时间兜底、多候选消歧或可组合表达式规则。
- 不支持百分比容差、多币种、汇率换算或商户级规则。
- 不改造 CSV 导入为 Spring Batch Job。
- 不允许对账自动修改账本或生成资金调整分录。
- 不提供渠道新增和删除页面；首期只允许启用或停用预置渠道。
- 不把固定耗时门槛加入 CI，因为共享 runner 和个人电脑性能不可比较。

## 5. 领域模型

### 5.1 渠道

新增 `ReconciliationChannel`：

- `id`
- `code`：不可变英文编码，唯一
- `displayName`：中文展示名称
- `active`
- `createdAt`
- `version`

Flyway 预置以下记录：

| code | displayName |
| --- | --- |
| `ALIPAY` | 支付宝 |
| `WECHAT_PAY` | 微信支付 |
| `UNION_PAY` | 银联 |

渠道只能启用或停用。停用渠道不能创建新批次，已有批次和历史查询不受影响。

迁移另建默认停用且不出现在上传下拉框的 `LEGACY_SYNTHETIC` 渠道，只用于承接升级前 `SYNTHETIC_CHANNEL` 批次。历史数据不会被错误标记成任一真实演示渠道。

### 5.2 规则定义

新增 `ReconciliationRule`，表示一个稳定的规则作用域：

- `id`
- `scopeType`：`DEFAULT` 或 `CHANNEL`
- `channelId`：仅 `CHANNEL` 必填
- `activeVersionId`：当前已发布版本，可为空
- `version`：乐观锁版本

数据库约束保证全平台最多一条 `DEFAULT`，每个渠道最多一条 `CHANNEL` 规则。

### 5.3 规则版本

新增 `ReconciliationRuleVersion`：

- `id`
- `ruleId`
- `versionNumber`：同一规则内从 1 递增
- `status`：`DRAFT` 或 `PUBLISHED`
- `amountToleranceCents`：固定金额容差，使用非负整数分
- `queryWindowHours`：对账查询窗口，取值 0 至 168
- `createdBy`、`createdAt`
- `publishedBy`、`publishedAt`：仅已发布版本必填

系统内部金额已经使用整数分，因此容差继续使用 `long` 分值，避免引入小数换算。页面按元展示和录入，并在 Web 边界精确转换为分。

每条规则最多有一个草稿。首次编辑创建草稿；后续编辑更新同一草稿。发布时锁定规则定义，校验草稿，将其变为不可修改的 `PUBLISHED` 版本并更新 `activeVersionId`。再次编辑时从当前版本复制出新草稿。

PostgreSQL 部分唯一索引保证每条规则最多一个 `DRAFT`，数据库触发器拒绝已发布版本的更新和删除。允许的唯一状态变化是发布事务中的 `DRAFT -> PUBLISHED`。

### 5.4 批次与运行

`ReconciliationBatch` 新增：

- `channelId`
- `ruleVersionId`

上传 CSV 时在同一事务内完成以下动作：

1. 校验所选渠道存在且启用。
2. 优先解析渠道当前已发布版本，没有时回退到默认当前已发布版本。
3. 如果仍没有已发布版本，拒绝导入。
4. 保存批次、锁定的规则版本和账单条目。

`ReconciliationRun` 保留现有尝试号和状态，并增加用于页面展示的 Spring Batch 执行标识、当前步骤、已处理数量、总处理数量和重启次数。总处理数量等于渠道条目数加查询窗口内的内部候选交易数，避免两步 Job 的进度超过 100%。一次人工重试仍创建新的 `ReconciliationRun`；同一运行内部的 checkpoint 续跑只增加重启次数，不增加尝试号。

运行状态继续使用 `QUEUED`、`RUNNING`、`SUCCEEDED` 和 `FAILED`。同一 JobInstance 的 checkpoint 重启允许 `FAILED -> RUNNING`；除此之外，失败运行不能被普通业务操作重新打开。

### 5.5 工作结果与正式结果

现有 `reconciliation_result` 继续保存成功完成批次的正式结果和异常案件。新增 `reconciliation_result_work` 保存 chunk 已提交的中间结果：

- `runId`
- `batchId`
- `statementEntryId`
- `paymentId`
- `resultType`
- `resolutionStatus`
- `createdAt`

工作表分别以 `(run_id, statement_entry_id)` 和 `(run_id, payment_id)` 保证幂等。这样 Spring Batch 可以逐块提交，又不会让失败运行的半成品出现在异常工作台。Job 成功时在一个事务内把当前运行的工作结果提升为正式结果、更新汇总并清理该批次工作结果。

## 6. 规则语义

### 6.1 流水号

`channelTransactionId` 与内部支付的 `channelReference` 必须完全一致。未找到内部交易时生成 `CHANNEL_ONLY`；查询窗口内未被任何渠道条目消费的内部交易生成 `INTERNAL_ONLY`。

### 6.2 金额容差

匹配到相同流水号后计算：

```text
absoluteDifference = abs(internalAmountCents - channelAmountCents)
```

当 `absoluteDifference <= amountToleranceCents` 时结果为 `MATCHED`，否则为 `AMOUNT_MISMATCH`。边界值等于容差时视为匹配。

### 6.3 查询窗口

账单 CSV 的最早和最晚 `occurredAt` 继续形成 `periodStart` 和 `periodEnd`。规则的 `queryWindowHours` 对内部支付查询做对称扩展：

```text
queryStart = periodStart - queryWindowHours
queryEnd = periodEnd + queryWindowHours
```

查询窗口只限定内部候选交易集合，不降低流水号精确匹配要求。

## 7. Spring Batch 架构

### 7.1 基础设施

- 引入 Spring Boot Batch starter，版本由 Spring Boot 3.5 依赖管理。
- JobRepository 使用现有 PostgreSQL 数据源。
- Spring Batch 官方 PostgreSQL 元数据表放在独立 `batch` schema，由 Flyway 迁移创建；关闭框架自动建表。
- Job 名称固定为 `reconciliationJob`，唯一识别参数为 `runId`。
- 原 `ReconciliationTaskDispatcher` 和专用 `ThreadPoolTaskExecutor` 被 Batch JobLauncher 替代。

### 7.2 Job 步骤

`reconciliationJob` 包含以下步骤：

1. `prepareReconciliationStep`：校验运行、批次和锁定规则，计算总处理数量。仅首次执行时清理当前运行可能存在的工作结果。
2. `matchStatementEntriesStep`：按稳定游标分页读取渠道条目，以 500 条为 chunk 批量查询相同流水号的内部支付，生成 `MATCHED`、`AMOUNT_MISMATCH` 或 `CHANNEL_ONLY` 工作结果。
3. `findInternalOnlyPaymentsStep`：按发生时间和 ID 稳定分页读取查询窗口内的成功充值，批量排除当前运行已消费的 payment ID，生成 `INTERNAL_ONLY` 工作结果。
4. `finalizeReconciliationStep`：校验工作结果数量，原子提升正式结果，更新批次和运行汇总并写审计日志。

两个 chunk Step 都使用数据库排序键进行确定性读取，并把最后提交位置保存在 `ExecutionContext`。Reader 通过扩展 `PaymentsApi` 的批量分页查询访问支付模块，不直接依赖支付模块内部 Repository，也不执行跨模块 SQL join。

Processor 只负责规则判断，不访问数据库。Writer 使用批量写入和数据库唯一约束保证重复执行安全。

JobExecution 监听器在每次首次执行或 checkpoint 重启前把应用运行状态置为 `RUNNING`，并在执行失败后记录稳定错误；状态切换不依赖只执行一次的准备 Step。成功状态只由最终 Step 在正式结果提交后写入。

### 7.3 进度

每个 chunk 成功提交后，监听器把已处理数量同步到 `ReconciliationRun`。页面展示：

- Spring Batch 状态
- 已处理数量和总数量
- 匹配数和差异数
- 当前运行尝试号
- checkpoint 重启次数
- 开始时间、完成时间和失败原因

进度只在事务成功提交后增加，不展示尚未落库的内存处理量。

## 8. 失败、重启和并发

### 8.1 失败分类

- `TransientDataAccessException` 最多自动重试 3 次。
- 数据格式、规则状态和业务约束错误不跳过记录，直接让 Job 失败。
- 页面保存最长 2000 字符的稳定错误信息，完整堆栈只写服务端日志。

不配置 `skip`，因为对账场景不能静默丢弃异常记录。

### 8.2 Checkpoint 续跑

同一 `runId` 对应同一 JobInstance。Job 失败或停止后，恢复操作使用相同参数创建新的 JobExecution；已完成 Step 不重复执行，失败 Step 从最后成功提交的 chunk 继续。工作表幂等约束作为 checkpoint 之外的第二层保护。

应用启动恢复器处理两类记录：

1. `QUEUED` 且没有 JobExecution 的运行重新提交。
2. 超过恢复阈值仍标记 `RUNNING`、但属于上一个应用进程的执行先结束为失败状态，再由 JobOperator 重启。

恢复器只对每个遗留执行自动尝试一次；再次失败后保留失败状态，等待管理员决定继续原运行或创建新的人工重试。

### 8.3 人工重试

现有人工重试语义保持不变：为批次创建更大的 `attemptNumber` 和新的 `runId`。新运行仍使用批次锁定的规则版本，不使用后来发布的版本。旧运行和 Spring Batch JobExecution 历史保留用于审计。

### 8.4 并发

- 现有活动运行部分唯一索引继续保证同一批次最多一个 `QUEUED` 或 `RUNNING` 运行。
- 规则发布使用规则定义行锁和乐观锁，保证一个作用域只有一个当前发布版本。
- Job 启动和恢复以 `runId` 作为幂等键。
- 渠道停用、规则发布和批次创建并发时，以批次创建事务读取到的已提交状态为准。

## 9. 管理页面

所有新增页面继续使用现有 Spring MVC、Thymeleaf、HTMX 和安全配置。

### 9.1 对账规则

新增 `/admin/reconciliation/rules`：

- 展示默认规则及三个渠道规则。
- 展示当前发布版本、金额容差、查询窗口、草稿状态和最近发布时间。
- 提供“编辑草稿”“发布”和“查看版本历史”命令。
- 发布前显示要发布的具体参数，并要求明确确认。

规则编辑页按元录入固定金额容差，按小时录入查询窗口。底层校验错误转为明确中文提示。

### 9.2 渠道列表

规则页同时展示预置渠道的启用状态，并允许启用或停用。首期不提供新增、修改编码和删除功能。

### 9.3 账单上传与批次详情

- 上传页增加渠道下拉框，只显示启用渠道。
- 选择渠道后展示其当前生效规则或默认回退规则。
- 批次列表和详情展示渠道中文名称及锁定的规则版本。
- 运行详情展示 Spring Batch 进度、重启次数和失败原因，并沿用 HTMX 自动刷新。
- 失败运行提供“从断点继续”命令；批次级“重新对账”继续创建新的运行尝试。页面明确区分两种行为。

## 10. 审计

以下动作写入现有不可变审计日志：

- 规则草稿保存
- 规则版本发布
- 渠道启用和停用
- 带渠道及规则版本的批次导入
- Job 启动、恢复、成功和失败

审计详情保存实体 ID、规则版本 ID 或运行 ID，不保存 CSV 内容和完整堆栈。

## 11. 数据库迁移

Flyway 迁移按以下顺序完成：

1. 创建渠道、规则定义和规则版本表、草稿唯一索引及已发布版本不可变触发器。
2. 插入三个预置渠道、默认规则和可运行的默认发布版本。
3. 创建默认停用的 `LEGACY_SYNTHETIC` 兼容渠道，为已有批次回填该渠道和默认规则版本，再把新外键改为非空。
4. 创建 `reconciliation_result_work` 及幂等索引。
5. 扩展运行表以保存 Batch 执行标识、进度和重启次数。
6. 创建 `batch` schema 和 Spring Batch PostgreSQL 元数据表。

迁移必须同时验证空库安装和从当前最新版本升级。已有账单保持原文件哈希和结果，不重算历史数据。

## 12. 测试策略

### 12.1 单元测试

- 渠道规则优先和默认规则回退。
- 没有已发布规则时拒绝创建批次。
- 金额差为 0、小于、等于和大于容差的边界。
- 查询窗口起止时间计算。
- 发布版本不可修改，编辑创建新草稿。
- Processor 的四种结果类型。

### 12.2 PostgreSQL 集成测试

- 同一规则并发发布只有一个成功当前版本。
- 两个 chunk Step 的确定性分页和完整结果。
- chunk 中途失败后从 checkpoint 继续。
- 重启不产生重复工作结果或正式结果。
- 失败运行的工作结果不出现在异常工作台。
- 人工新尝试保留旧运行且继续使用锁定规则版本。
- 应用启动恢复 `QUEUED` 和遗留 `RUNNING` 作业。
- Flyway 空库安装和当前版本升级。

### 12.3 Web 与安全测试

- 规则列表、草稿编辑、发布确认和版本历史全部显示中文。
- 渠道启停、上传渠道选择和规则预览。
- 批次与运行详情的进度轮询和结束状态。
- 未登录请求、CSRF 和现有管理员权限保护保持有效。

### 12.4 10 万笔性能与恢复验证

提供可重复的数据生成命令和独立 Maven profile，不在默认 CI 中运行。验收条件：

1. 单批次 10 万条渠道记录可以完成。
2. 正式结果总数和分类汇总符合生成器预期。
3. 页面进度随 chunk 提交单调增加。
4. 确定性失败后，同一运行可以从 checkpoint 继续；启动恢复集成测试独立验证遗留运行的恢复决策。自动化测试不把同 JVM 恢复表述为真实进程重启。
5. 最终不存在重复结果，且失败运行的半成品不可见。
6. 输出总耗时、每秒处理量和重启次数，供 README 演示记录使用。

默认 GitHub Actions 继续运行单元测试、Web 测试和较小数据量的 PostgreSQL 集成测试。

## 13. 文档与迁移体验

更新 `README.md`、`docs/USER_GUIDE.md` 和 `docs/MIGRATION.md`：

- 说明 JDK 17、PostgreSQL 17、Git 和 Maven Wrapper 前置条件。
- 给出数据库创建、环境变量、启动和测试命令。
- 说明 Flyway 自动创建业务及 Spring Batch 表。
- 给出预置管理员、规则发布、渠道账单上传、运行观察和失败续跑演示步骤。
- 给出 10 万笔数据生成和压测命令。

文档不得依赖当前公司电脑的绝对路径、Homebrew 安装位置或本地已有数据库名。

## 14. 完成标准

1. 所有默认测试和 GitHub Actions 通过。
2. 新页面中文展示完整，底层标识保持英文。
3. 已有导入、运行历史和异常案件流程无回归。
4. 规则版本、批次、运行、Batch 元数据和审计日志可以串成完整审计链。
5. 10 万笔演示测试满足第 12.4 节验收条件。
6. 在一台只有规定前置软件的个人电脑上，可以按照文档完成迁移和启动。
