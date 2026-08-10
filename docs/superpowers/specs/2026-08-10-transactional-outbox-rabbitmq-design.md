# Transactional Outbox 与 RabbitMQ 站内通知设计

## 1. 背景

平台当前在一个 Spring Boot 模块化单体中同步完成支付记账、审计和渠道对账。支付成功由 `PaymentProcessor.process()` 的独立事务提交，对账成功由 `ReconciliationStore.promoteWorkResults()` 的事务提交。现有系统没有消息中间件，不能展示数据库事务与异步消息之间的可靠衔接，也没有异步消费去重、重试和死信处理能力。

本阶段在不改变支付记账和 Spring Batch 对账职责的前提下，引入 Transactional Outbox 和 RabbitMQ。消息消费的业务结果是生成管理员站内通知。

## 2. 目标

- 支付或对账的业务状态与 Outbox 事件在同一个 PostgreSQL 事务中提交。
- Outbox 事件可靠投递到 RabbitMQ，Broker 暂时不可用时不丢失事件。
- 消费者按事件 ID 去重，重复投递不会生成重复通知。
- 消费异常经过有限次数重试后进入死信队列，不形成无限循环。
- 管理页面能够查看站内通知、Outbox 状态和死信队列数量。
- Docker Compose 一键启动应用、PostgreSQL 17 和 RabbitMQ，迁移到其他电脑后可按手册运行。
- 保持模块化单体，不引入与目标无关的基础设施。

## 3. 非目标

- 不使用 RabbitMQ 调度或拆分 Spring Batch 对账作业。
- 不将支付、对账或通知拆成独立微服务。
- 不引入 Kafka、Debezium、Redis、Kubernetes 或分布式事务框架。
- 不宣称 exactly-once；系统提供 at-least-once 投递和幂等消费。
- 不在本阶段实现死信消息的业务页面重放。RabbitMQ 管理台负责查看死信内容。
- 不发送支付失败或对账失败通知。失败继续由现有状态与审计日志表达。
- 不在本阶段加入 Gatling 或 k6；并发压测作为下一阶段独立工作。

## 4. 方案选择

### 4.1 采用的方案

采用应用内 Outbox Publisher、Spring AMQP 和 PostgreSQL 状态表：

1. 业务事务调用 `OutboxApi` 写入事件。
2. 后台 Publisher 从 PostgreSQL 领取待发送事件。
3. Publisher 通过 RabbitMQ publisher confirm 判断 Broker 是否接收。
4. 通知消费者在本地事务中完成去重和通知写入。

该方案显式保留事务、投递、重试和消费去重的实现边界，适合当前项目规模，也便于测试和讲解。

### 4.2 未采用的方案

- Spring Modulith 事件外部化可以减少代码，但会隐藏本项目希望展示的 Outbox 状态流转和故障恢复细节。
- Debezium CDC 能消除应用轮询，但会显著增加部署复杂度，并引入本阶段不需要的外部组件。

## 5. 模块边界

新增两个 Spring Modulith 应用模块：

- `messaging`：定义 Outbox 公共 API、事件信封、Outbox 持久化、Publisher、RabbitMQ 拓扑和消息运维查询。
- `notifications`：订阅 RabbitMQ 事件、执行消费去重、创建和查询站内通知。

依赖方向：

- `payments -> messaging`
- `reconciliation -> messaging`
- `notifications -> messaging`，只依赖公开的事件信封契约，不访问 Outbox 内部仓储。

`messaging` 不依赖支付或对账模块。事件 Payload 由生产模块构造，避免消息模块反向读取业务数据。

## 6. 业务事件

### 6.1 事件类型

- `PAYMENT_SUCCEEDED`：充值、转账、退款或冲正成功后产生。
- `RECONCILIATION_COMPLETED`：一次对账运行和批次成功完成后产生。

### 6.2 JSON 信封

```json
{
  "eventId": "c3bbc59e-e517-4ff0-83ae-cf6a27710a91",
  "eventType": "PAYMENT_SUCCEEDED",
  "schemaVersion": 1,
  "aggregateType": "PAYMENT",
  "aggregateId": "0cd7a0a1-6696-4ba8-a703-597d3455ec21",
  "occurredAt": "2026-08-10T10:00:00Z",
  "payload": {
    "paymentType": "TRANSFER",
    "amountCents": 10000,
    "channelReference": "TRANSFER-123"
  }
}
```

所有字段名、事件类型和枚举使用英文。管理页面将其映射为中文。Payload 只保存生成通知所需的数据，不包含管理员凭据、幂等键或账户敏感信息。

`PAYMENT_SUCCEEDED` 的 Payload 包含 `paymentType`、`amountCents` 和 `channelReference`。`RECONCILIATION_COMPLETED` 的 Payload 包含 `batchId`、`runId`、`matchedRows` 和 `differenceRows`。

## 7. 数据模型

Flyway 新增 `messaging` 和 `notification` schema，并创建以下表。

### 7.1 `messaging.outbox_event`

- `id uuid primary key`：同时作为消息 `eventId`。
- `aggregate_type varchar(64) not null`
- `aggregate_id varchar(128) not null`
- `event_type varchar(64) not null`
- `schema_version integer not null`
- `payload jsonb not null`
- `status varchar(16) not null`
- `attempt_count integer not null default 0`
- `next_attempt_at timestamptz not null`
- `locked_at timestamptz null`
- `published_at timestamptz null`
- `last_error varchar(2000) null`
- `occurred_at timestamptz not null`
- `created_at timestamptz not null`

状态只能是 `PENDING`、`PUBLISHING`、`PUBLISHED` 或 `FAILED`。为 `(status, next_attempt_at, created_at)` 建立领取索引。状态检查约束保证非法状态不能写入。

### 7.2 `notification.consumed_message`

- `event_id uuid primary key`
- `queue_name varchar(128) not null`
- `event_type varchar(64) not null`
- `consumed_at timestamptz not null`

主键是幂等屏障。同一事件再次到达时，不再执行通知写入。

### 7.3 `notification.notification`

- `id uuid primary key`
- `event_id uuid not null unique`
- `notification_type varchar(64) not null`
- `title varchar(200) not null`
- `content varchar(1000) not null`
- `aggregate_type varchar(64) not null`
- `aggregate_id varchar(128) not null`
- `created_at timestamptz not null`
- `read_at timestamptz null`

`event_id` 唯一约束作为第二层防重保护。当前系统只有管理员角色，通知不按用户拆分。

## 8. 支付与对账事务

### 8.1 支付成功

`PaymentProcessor.process()` 在账本分录、支付状态和成功审计写入后，通过 `OutboxApi.append()` 写入 `PAYMENT_SUCCEEDED`。这些写操作处于同一个 `REQUIRES_NEW` 事务中。任一步骤异常时整个事务回滚，不留下 Outbox 事件。

失败支付由 `PaymentProcessor.fail()` 更新状态和审计，不产生成功事件。

### 8.2 对账完成

`ReconciliationStore.promoteWorkResults()` 在结果提升、批次完成、运行成功和审计写入后，通过 `OutboxApi.append()` 写入 `RECONCILIATION_COMPLETED`。所有写操作共享当前事务。结果数量不完整或状态更新失败时整个事务回滚，不留下成功事件。

## 9. Outbox Publisher

Publisher 默认每秒执行一次，每次最多领取 50 条到期的 `PENDING` 事件。领取过程使用 PostgreSQL `FOR UPDATE SKIP LOCKED`，并把事件原子更新为 `PUBLISHING`、记录 `locked_at` 和增加 `attempt_count`。

领取事务提交后逐条发布，避免在网络调用期间长时间持有数据库行锁。RabbitMQ publisher confirm 成功后把事件标记为 `PUBLISHED` 并记录 `published_at`。Confirm 失败或超时后按指数退避重新标记为 `PENDING`，错误摘要写入 `last_error`。

退避从 1 秒开始，最大 60 秒。第 10 次发送失败后状态改为 `FAILED`，停止自动投递。管理页面提供“重新投递”操作，把单个 `FAILED` 事件恢复为立即到期的 `PENDING`，将新一轮 `attempt_count` 重置为 0，并保留最后错误直到重新发布成功。

应用启动及定时扫描时，把 `locked_at` 早于 60 秒的 `PUBLISHING` 事件恢复为 `PENDING`。如果 Broker 已接收消息而应用在标记 `PUBLISHED` 前崩溃，事件会再次发送；这是 at-least-once 的预期行为，由消费者去重处理。

同一 Outbox 事件在同一时刻只能由一个 Publisher 领取。项目仍遵守现有单应用实例约束，但领取算法不依赖该约束才能防止并发重复领取。

## 10. RabbitMQ 拓扑

- Topic exchange：`ledger.events`
- 通知队列：`notification.events.v1`
- Routing keys：`payment.succeeded.v1`、`reconciliation.completed.v1`
- Dead-letter exchange：`ledger.events.dlx`
- 死信队列：`notification.events.v1.dlq`
- 死信 routing key：`notification.dead.v1`

交换机和队列均持久化，业务消息设置为持久消息。应用启动时通过 Spring AMQP 声明拓扑。消息使用 JSON，不使用 Java 原生序列化。

## 11. 消费、重试与死信

通知 Listener 收到消息后调用带事务的消费服务：

1. 校验信封版本、事件类型和必要 Payload 字段。
2. 对 `notification.consumed_message` 执行 `INSERT ... ON CONFLICT DO NOTHING`。
3. 如果插入数为 0，说明已消费，正常返回并确认消息。
4. 如果首次消费，写入中文站内通知，然后提交事务。

Listener 成功返回后由容器确认消息。数据库事务失败时抛出异常，不确认当前处理。消费最多进行 3 次处理尝试，退避约 1 秒和 2 秒。第三次仍失败时拒绝消息且不重新入主队列，由 RabbitMQ 路由到死信队列。

不启动死信消费者，也不自动重放死信。该约束防止永久错误消息形成循环。集成测试和用户手册通过发布格式错误但可路由的消息验证死信行为。

## 12. 管理页面

所有展示文本使用中文，底层路由、类名、字段和枚举保持英文。

### 12.1 `/admin/notifications`

- 按创建时间倒序展示通知类型、标题、内容、关联业务编号、创建时间和已读状态。
- 支持将单条通知标记为已读。
- 页面使用现有 Thymeleaf、HTMX 和管理端布局，不引入前端框架。

### 12.2 `/admin/messaging`

- 展示 `PENDING`、`PUBLISHING`、`PUBLISHED`、`FAILED` 数量。
- 展示最近 Outbox 事件的类型、业务 ID、状态、尝试次数、时间和最后错误。
- 通过 RabbitMQ 队列属性展示主队列和死信队列的就绪消息数量；Broker 不可用时页面显示“消息服务不可用”，但页面本身仍可打开。
- 对 `FAILED` 事件提供带 CSRF 保护的单条重新投递操作。

导航栏增加“站内通知”和“消息运维”。不在页面展示 RabbitMQ 密码或完整消息 Payload。

## 13. 配置与部署

新增 Spring AMQP 依赖、RabbitMQ 连接配置、Publisher 调度配置和 Jackson JSON 配置。默认生产配置从环境变量读取：

- `RABBITMQ_HOST`
- `RABBITMQ_PORT`
- `RABBITMQ_USERNAME`
- `RABBITMQ_PASSWORD`
- `OUTBOX_PUBLISH_INTERVAL`

仓库新增多阶段 `Dockerfile`，使用 JDK 17 构建并使用 JRE 17 运行。`compose.yaml` 扩展为：

- `db`：PostgreSQL 17
- `rabbitmq`：RabbitMQ 4 management 镜像，开放 AMQP `5672` 和管理端 `15672`
- `app`：构建当前项目，等待数据库和 RabbitMQ 健康后启动，开放 `8080`

`.env.example` 记录所需变量但不提交真实密码。推荐迁移启动命令为 `docker compose up --build`。同时保留宿主机执行 `./mvnw spring-boot:run`、Compose 只启动依赖的开发方式。

Actuator 健康检查分别暴露数据库和 RabbitMQ 状态。Compose 正常启动顺序会等待 Broker 健康，但应用本身允许在 RabbitMQ 暂时不可用时启动，Listener 和 Publisher 持续重连。Broker 中断不会影响同步支付和对账事务，事件会留在 Outbox 等待恢复。

## 14. 安全与数据边界

- 管理页面继续使用现有 Spring Security 管理员认证和 CSRF 防护。
- RabbitMQ 使用专用非默认账号，凭据仅从环境变量提供。
- 日志和页面不输出 RabbitMQ 密码及完整 Payload。
- 错误摘要最大保存 2000 字符，避免无界增长。
- 通知内容来自受控事件字段，不直接渲染未转义 HTML。
- 示例和测试只使用合成数据。

## 15. 测试策略

### 15.1 默认测试

- Outbox 事件和 JSON 信封校验单元测试。
- 支付成功写入事件、支付回滚不写事件的模块测试。
- 对账完成写入事件、对账回滚不写事件的模块测试。
- Outbox 领取、退避、失败和过期锁恢复的 PostgreSQL 集成测试。
- 消费同一事件两次只生成一条通知的幂等测试。
- 通知已读和管理页面权限、中文展示测试。
- Flyway 迁移、模块边界和现有回归测试。

### 15.2 RabbitMQ 集成测试

- publisher confirm 后 Outbox 状态变为 `PUBLISHED`。
- 暂停或断开 RabbitMQ 时事件保留，恢复后成功发送。
- 重复发布同一事件只生成一条通知。
- 格式错误消息在 3 次处理尝试后进入 `notification.events.v1.dlq`。
- Compose 启动后的数据库、RabbitMQ、应用健康检查通过。

GitHub Actions 启动 PostgreSQL 和 RabbitMQ service，执行完整消息集成测试。本地默认测试仍需要现有 PostgreSQL 测试库；RabbitMQ 集成测试使用独立 Maven profile，便于在明确启动 Broker 后运行。

## 16. 验收标准

- `./mvnw clean verify` 通过，现有支付、账本、对账和审计行为没有回归。
- 消息集成测试 profile 在 PostgreSQL 与 RabbitMQ 可用时通过。
- 支付成功和对账完成后，管理员能在站内通知页面看到各一条通知。
- 同一 `eventId` 重复发送不会生成第二条通知。
- RabbitMQ 中断期间业务事务仍可提交，Outbox 不丢事件；恢复后事件最终发布。
- 永久消费错误经过有限重试后进入死信队列。
- `docker compose up --build` 能在新电脑启动三个服务。
- `README.md`、`docs/USER_GUIDE.md` 和 `docs/MIGRATION.md` 包含安装、启动、验证、故障演示、恢复和迁移步骤。

## 17. 后续阶段

该阶段完成并验证后，再以独立规格增加 Gatling 或 k6 并发压测。压测重点是支付幂等与账户行锁、Outbox 积压恢复吞吐和重复消息消费，不在本设计中预先实现。
