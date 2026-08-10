# Transactional Outbox 与 RabbitMQ 站内通知 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有模块化单体中实现支付成功和对账完成事件的 Transactional Outbox 可靠投递、RabbitMQ 幂等通知消费、有限重试与死信处理，并提供中文管理页面和一键迁移部署。

**Architecture:** 支付与对账事务通过 `OutboxApi` 把版本化事件写入 PostgreSQL，独立 Publisher 使用 `FOR UPDATE SKIP LOCKED` 领取并通过 publisher confirm 投递 RabbitMQ。通知 Listener 以 `eventId` 为幂等键，在同一数据库事务中记录消费和创建站内通知；永久失败消息由 RabbitMQ 路由至 DLQ。

**Tech Stack:** Java 17、Spring Boot 3.5、Spring Modulith、Spring Data JPA、Spring JDBC、Spring AMQP、RabbitMQ 4、PostgreSQL 17、Flyway、Thymeleaf、HTMX、JUnit 5、MockMvc、Awaitility、Docker Compose、GitHub Actions

**Scope Guard:** 保留 Spring Batch 处理批量对账；本计划不引入 Kafka、Debezium、Redis、微服务、Kubernetes、Gatling 或 k6。

---

## File Map

### Database and public contracts

- `src/main/resources/db/migration/V16__add_outbox_and_notifications.sql`: create the `messaging` and `notification` schemas, tables, checks, and claim index.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/package-info.java`: declare a closed Spring Modulith module.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxApi.java`: expose transactional event append only.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxCommand.java`: carry the aggregate, event type, version, payload, and occurrence time.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/EventType.java`: define the two supported event types and routing keys.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/EventEnvelope.java`: define the stable RabbitMQ JSON contract.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/RabbitTopology.java`: expose stable exchange and queue names to the notification module without leaking messaging internals.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/MessagingOperationsApi.java`: expose read-only Outbox operations, queue depth, and failed-event retry to the web layer.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxEventView.java`, `OutboxSummary.java`, `QueueDepths.java`: immutable operational views.
- `src/main/java/io/github/user32694/ledgerplatform/notifications/package-info.java`: declare a module that depends only on `messaging`.
- `src/main/java/io/github/user32694/ledgerplatform/notifications/NotificationsApi.java`, `NotificationView.java`: expose notification listing and mark-read operations.

### Outbox internals

- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxEventEntity.java`: map the Outbox row and its state transitions.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxEventRepository.java`: provide recent-event and status-count queries.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxService.java`: validate and append events in the caller transaction.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxStore.java`: claim due rows and persist publish, retry, failed, stale-lock, and manual-retry transitions.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxPublisher.java`: schedule and orchestrate a batch without owning database transactions.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitEventPublisher.java`: serialize an envelope, publish it, and await correlated publisher confirm.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitMessagingConfiguration.java`: declare exchange, queues, bindings, JSON support, retry advice, and scheduling.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/MessagingProperties.java`: bind batch, confirm-timeout, and stale-lock settings.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitQueueProbe.java`: read main and DLQ ready counts without exposing credentials.

### Notification internals and web

- `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationEntity.java`, `NotificationRepository.java`: store and query notifications.
- `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/ConsumedMessageStore.java`: provide the `INSERT ... ON CONFLICT DO NOTHING` idempotency barrier.
- `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationService.java`: consume an envelope transactionally and implement the public API.
- `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationMessageListener.java`: deserialize inside retry advice and delegate to the service.
- `src/main/java/io/github/user32694/ledgerplatform/notifications/web/NotificationWebController.java`: render notifications and handle mark-read POST.
- `src/main/java/io/github/user32694/ledgerplatform/messaging/web/MessagingWebController.java`: render Outbox operations and handle failed-event retry POST.
- `src/main/resources/templates/admin/notifications.html`, `messaging.html`: Chinese admin pages.
- `src/main/resources/templates/admin/layout.html`: add two navigation links.

### Runtime, tests, and documentation

- `pom.xml`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`, `src/test/resources/application-messaging-integration.yml`: add Spring AMQP and isolate real-Broker tests.
- `Dockerfile`, `.dockerignore`, `compose.yaml`, `.env.example`: provide portable three-service startup.
- `.github/workflows/build.yml`: start RabbitMQ and run the messaging integration profile.
- `README.md`, `docs/USER_GUIDE.md`, `docs/MIGRATION.md`: document operation, failure demonstrations, recovery, and migration.
- Existing module, migration, payment, reconciliation, web, and documentation tests are extended; focused new tests live under `messaging` and `notifications`.

### Task 1: Add database contracts and module boundaries

**Files:**
- Create: `src/main/resources/db/migration/V16__add_outbox_and_notifications.sql`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/package-info.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/package-info.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`

- [ ] **Step 1: Write failing migration and module tests**

Add a `createsOutboxAndNotificationTablesWithConstraints()` test that checks the exact table names and rejects invalid Outbox status and duplicate notification `event_id` values:

```java
@Test
@Transactional
void createsOutboxAndNotificationTablesWithConstraints() {
    assertThat(jdbcTemplate.queryForList("""
            SELECT table_schema || '.' || table_name
            FROM information_schema.tables
            WHERE table_schema IN ('messaging', 'notification')
            ORDER BY table_schema, table_name
            """, String.class)).containsExactly(
                    "messaging.outbox_event",
                    "notification.consumed_message",
                    "notification.notification");

    assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO messaging.outbox_event
                (id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                 status, attempt_count, next_attempt_at, occurred_at, created_at)
            VALUES (?, 'PAYMENT', 'p-1', 'PAYMENT_SUCCEEDED', 1, '{}'::jsonb,
                    'UNKNOWN', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

    UUID eventId = UUID.randomUUID();
    insertNotification(eventId);
    assertThatThrownBy(() -> insertNotification(eventId))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

Add a private `insertNotification(UUID eventId)` helper that inserts every required notification column with fixed synthetic values and a random notification ID.

Update `ModularityTest` to expect `messaging` and `notifications`, assert `messaging` has no allowed dependencies, and assert `notifications` allows only `messaging`.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```bash
./mvnw -Dtest=MigrationIntegrationTest,ModularityTest test
```

Expected: failure because the two schemas/modules do not exist.

- [ ] **Step 3: Add the immutable database contract**

Create V16 with explicit checks and indexes:

```sql
CREATE SCHEMA messaging;
CREATE SCHEMA notification;

CREATE TABLE messaging.outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    event_type varchar(64) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    locked_at timestamptz,
    published_at timestamptz,
    last_error varchar(2000),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'))
);
CREATE INDEX ix_outbox_event_claim
    ON messaging.outbox_event (status, next_attempt_at, created_at);

CREATE TABLE notification.consumed_message (
    event_id uuid PRIMARY KEY,
    queue_name varchar(128) NOT NULL,
    event_type varchar(64) NOT NULL,
    consumed_at timestamptz NOT NULL
);

CREATE TABLE notification.notification (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    notification_type varchar(64) NOT NULL,
    title varchar(200) NOT NULL,
    content varchar(1000) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz
);
CREATE INDEX ix_notification_created
    ON notification.notification (created_at DESC, id DESC);
```

Declare the module packages:

```java
@org.springframework.modulith.ApplicationModule
package io.github.user32694.ledgerplatform.messaging;
```

```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"messaging"})
package io.github.user32694.ledgerplatform.notifications;
```

- [ ] **Step 4: Run the focused tests and verify they pass**

Run the command from Step 2. Expected: both test classes pass and Flyway reports V16 applied.

- [ ] **Step 5: Commit the database contract**

```bash
git add src/main/resources/db/migration/V16__add_outbox_and_notifications.sql \
  src/main/java/io/github/user32694/ledgerplatform/messaging/package-info.java \
  src/main/java/io/github/user32694/ledgerplatform/notifications/package-info.java \
  src/test/java/io/github/user32694/ledgerplatform/MigrationIntegrationTest.java \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java
git commit -m "feat: 增加可靠消息与通知数据契约"
```

### Task 2: Implement transactional Outbox append

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/EventType.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxCommand.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/EventEnvelope.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxEventEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxEventRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxService.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/messaging/OutboxModuleTest.java`

- [ ] **Step 1: Write failing append and validation tests**

Use `@ApplicationModuleTest`, `@ActiveProfiles("test")`, and SQL cleanup. Verify a valid command stores a `PENDING` JSONB row and blank aggregate data is rejected without a row:

```java
var id = outboxApi.append(new OutboxCommand(
        EventType.PAYMENT_SUCCEEDED,
        "PAYMENT",
        paymentId.toString(),
        1,
        Map.of("paymentType", "TOP_UP", "amountCents", 500L,
                "channelReference", "TOPUP-1"),
        Instant.parse("2026-08-10T10:00:00Z")));

assertThat(jdbcTemplate.queryForMap("""
        SELECT status, event_type, payload->>'paymentType' AS payment_type
        FROM messaging.outbox_event WHERE id = ?
        """, id)).containsEntry("status", "PENDING")
        .containsEntry("event_type", "PAYMENT_SUCCEEDED")
        .containsEntry("payment_type", "TOP_UP");
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./mvnw -Dtest=OutboxModuleTest test
```

Expected: compilation failure because the public Outbox types do not exist.

- [ ] **Step 3: Add the public event contract**

Define one enum and two records; defensively copy the payload:

```java
public enum EventType {
    PAYMENT_SUCCEEDED("payment.succeeded.v1"),
    RECONCILIATION_COMPLETED("reconciliation.completed.v1");

    private final String routingKey;
    EventType(String routingKey) { this.routingKey = routingKey; }
    public String routingKey() { return routingKey; }
}

public record OutboxCommand(
        EventType eventType,
        String aggregateType,
        String aggregateId,
        int schemaVersion,
        Map<String, Object> payload,
        Instant occurredAt) {
    public OutboxCommand {
        payload = payload == null ? null : Map.copyOf(payload);
    }
}

public record EventEnvelope(
        UUID eventId,
        EventType eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        JsonNode payload) {}

public interface OutboxApi {
    UUID append(OutboxCommand command);
}
```

- [ ] **Step 4: Implement JPA persistence without starting a new transaction**

Map `payload` with `@JdbcTypeCode(SqlTypes.JSON)` and implement `OutboxService.append()` as a normal `@Transactional` method. Validate non-null event type/time/payload, positive version, aggregate type length 64, and aggregate ID length 128. Create a UUID once, convert the payload with `ObjectMapper.valueToTree`, and save a `PENDING` entity with `nextAttemptAt == createdAt`.

```java
@Override
@Transactional
public UUID append(OutboxCommand command) {
    validate(command);
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    UUID id = UUID.randomUUID();
    repository.save(new OutboxEventEntity(
            id, command.aggregateType().strip(), command.aggregateId().strip(),
            command.eventType(), command.schemaVersion(),
            objectMapper.valueToTree(command.payload()), OutboxStatus.PENDING,
            0, createdAt, null, null, null,
            command.occurredAt(), createdAt));
    return id;
}
```

Keep `OutboxStatus` package-private inside `messaging.internal`; only operational views expose its name as a string.

- [ ] **Step 5: Run the module test and full modularity test**

```bash
./mvnw -Dtest=OutboxModuleTest,ModularityTest test
```

Expected: PASS; the module API has no dependency on payment or reconciliation types.

- [ ] **Step 6: Commit transactional append**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/messaging \
  src/test/java/io/github/user32694/ledgerplatform/messaging/OutboxModuleTest.java
git commit -m "feat: 实现事务型消息事件写入"
```

### Task 3: Implement Outbox claim, retry, and stale-lock recovery

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/ClaimedOutboxEvent.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxStore.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxStoreTest.java`

- [ ] **Step 1: Write failing lifecycle tests**

Insert deterministic rows with `JdbcTemplate`, then verify:

```java
assertThat(store.claimDue(Instant.parse("2026-08-10T10:00:00Z"), 50))
        .extracting(ClaimedOutboxEvent::id)
        .containsExactly(dueId);
assertThat(status(dueId)).isEqualTo("PUBLISHING");
assertThat(attemptCount(dueId)).isEqualTo(1);

store.recordFailure(dueId, "broker unavailable", Instant.parse("2026-08-10T10:00:00Z"));
assertThat(status(dueId)).isEqualTo("PENDING");
assertThat(nextAttemptAt(dueId)).isEqualTo(Instant.parse("2026-08-10T10:00:01Z"));
```

Add separate tests for the tenth failure becoming `FAILED`, successful publish clearing locks, stale `PUBLISHING` recovery, `SKIP LOCKED` excluding a row locked by a second transaction, and manual retry accepting only `FAILED` rows while resetting `attempt_count` to 0.

- [ ] **Step 2: Run the lifecycle test and verify it fails**

```bash
./mvnw -Dtest=OutboxStoreTest test
```

Expected: compilation failure because `OutboxStore` does not exist.

- [ ] **Step 3: Implement atomic claim with PostgreSQL**

Use one `@Transactional(propagation = REQUIRES_NEW)` JDBC statement:

```sql
WITH candidates AS (
    SELECT id
    FROM messaging.outbox_event
    WHERE status = 'PENDING' AND next_attempt_at <= ?
    ORDER BY next_attempt_at, created_at
    FOR UPDATE SKIP LOCKED
    LIMIT ?
)
UPDATE messaging.outbox_event event
SET status = 'PUBLISHING',
    attempt_count = event.attempt_count + 1,
    locked_at = ?
FROM candidates
WHERE event.id = candidates.id
RETURNING event.id, event.aggregate_type, event.aggregate_id,
          event.event_type, event.schema_version, event.payload,
          event.attempt_count, event.occurred_at
```

Map the result to `ClaimedOutboxEvent`, including `JsonNode payload`. Do not promise cross-row publish order; correctness depends on event identity and idempotency, not ordering between unrelated aggregates.

- [ ] **Step 4: Implement terminal and recovery transitions**

Use separate `REQUIRES_NEW` methods. On failure, calculate `min(2^(attemptCount-1), 60)` seconds; attempts 1-9 return to `PENDING`, attempt 10 becomes `FAILED`. Truncate errors to 2000 characters. `recordPublished` requires `PUBLISHING`, sets `published_at`, clears `locked_at` and clears `last_error`; stale recovery updates rows with `locked_at < cutoff`; manual retry requires `FAILED`, sets `PENDING`, `attempt_count = 0`, and `next_attempt_at = now` while retaining the last error until a successful publish.

```java
long delaySeconds = Math.min(1L << Math.min(attemptCount - 1, 6), 60L);
String nextStatus = attemptCount >= 10 ? "FAILED" : "PENDING";
```

- [ ] **Step 5: Run lifecycle and migration tests**

```bash
./mvnw -Dtest=OutboxStoreTest,MigrationIntegrationTest test
```

Expected: PASS, including the concurrent lock test.

- [ ] **Step 6: Commit lifecycle persistence**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/messaging/internal \
  src/test/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxStoreTest.java
git commit -m "feat: 实现消息领取重试与崩溃恢复"
```

### Task 4: Publish Outbox events with RabbitMQ confirms

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/RabbitTopology.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/MessagingProperties.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitMessagingConfiguration.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitEventPublisher.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxPublisher.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxPublisherTest.java`

- [ ] **Step 1: Write failing publisher orchestration tests**

Mock `OutboxStore` and `RabbitEventPublisher`. Verify one scheduled pass first recovers stale claims, claims no more than 50 rows, records published only after gateway success, and records failure after gateway exception:

```java
publisher.publishDueEvents();

inOrder.verify(store).recoverStale(any(Instant.class));
inOrder.verify(store).claimDue(any(Instant.class), eq(50));
inOrder.verify(gateway).publish(event);
inOrder.verify(store).recordPublished(eq(event.id()), any(Instant.class));
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./mvnw -Dtest=OutboxPublisherTest test
```

Expected: compilation failure because the publisher classes do not exist.

- [ ] **Step 3: Add Spring AMQP and isolated test configuration**

Add `spring-boot-starter-amqp`. Configure correlated confirms, mandatory publishing, environment-backed connection values, and application properties:

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:ledger_app}
    password: ${RABBITMQ_PASSWORD:ledger_app}
    connection-timeout: 2s
    publisher-confirm-type: correlated
    publisher-returns: true
app:
  messaging:
    publisher-enabled: true
    publish-interval: PT1S
    batch-size: 50
    confirm-timeout: PT5S
    stale-lock-timeout: PT60S
```

In `application-test.yml`, set `app.messaging.publisher-enabled: false`, `spring.rabbitmq.dynamic: false`, and `spring.rabbitmq.listener.simple.auto-startup: false` so default tests never require a Broker.

- [ ] **Step 4: Declare durable topology and correlated-confirm publishing**

Define constants in the public `RabbitTopology` class exactly as specified and declare durable exchange, main queue with DLX arguments, durable DLQ, and bindings. Add a `Clock.systemUTC()` bean so scheduling can be deterministic in unit tests. Convert `ClaimedOutboxEvent` to `EventEnvelope`, serialize with the application `ObjectMapper`, publish a persistent JSON `Message`, and wait for the correlation future:

```java
CorrelationData correlation = new CorrelationData(event.id().toString());
rabbitTemplate.send(
        RabbitTopology.EVENT_EXCHANGE,
        event.eventType().routingKey(),
        MessageBuilder.withBody(objectMapper.writeValueAsBytes(event.toEnvelope()))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.id().toString())
                .build(),
        correlation);
CorrelationData.Confirm confirm = correlation.getFuture()
        .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
if (!confirm.isAck() || correlation.getReturned() != null) {
    throw new IllegalStateException("RabbitMQ did not confirm event " + event.id());
}
```

- [ ] **Step 5: Implement the scheduled orchestrator**

Enable scheduling inside the messaging configuration. Guard the Publisher with `@ConditionalOnProperty(name = "app.messaging.publisher-enabled", havingValue = "true", matchIfMissing = true)` and use:

```java
@Scheduled(fixedDelayString = "${app.messaging.publish-interval:PT1S}")
void publishDueEvents() {
    Instant now = clock.instant();
    store.recoverStale(now.minus(properties.staleLockTimeout()));
    for (var event : store.claimDue(now, properties.batchSize())) {
        try {
            gateway.publish(event);
            store.recordPublished(event.id(), clock.instant());
        } catch (Exception failure) {
            store.recordFailure(event.id(), stableMessage(failure), clock.instant());
        }
    }
}
```

- [ ] **Step 6: Run publisher and context tests**

```bash
./mvnw -Dtest=OutboxPublisherTest,ApplicationContextTest,OutboxModuleTest test
```

Expected: PASS without a local RabbitMQ process.

- [ ] **Step 7: Commit RabbitMQ publishing**

```bash
git add pom.xml src/main/resources/application.yml src/test/resources/application-test.yml \
  src/main/java/io/github/user32694/ledgerplatform/messaging/internal \
  src/test/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxPublisherTest.java
git commit -m "feat: 通过RabbitMQ确认可靠投递消息"
```

### Task 5: Produce events inside payment and reconciliation transactions

**Files:**
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/package-info.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/payments/internal/PaymentProcessor.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/package-info.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/reconciliation/internal/ReconciliationStore.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java`

- [ ] **Step 1: Write failing transaction-boundary assertions**

Include `messaging` in both module tests and delete Outbox rows before/after each test. Extend successful payment and completed reconciliation tests:

```java
assertThat(jdbcTemplate.queryForMap("""
        SELECT event_type, aggregate_type, aggregate_id,
               payload->>'paymentType' AS payment_type
        FROM messaging.outbox_event
        """)).containsEntry("event_type", "PAYMENT_SUCCEEDED")
        .containsEntry("aggregate_type", "PAYMENT")
        .containsEntry("aggregate_id", first.id().toString())
        .containsEntry("payment_type", "TOP_UP");
```

For a rejected payment, query by its payment ID and assert no `PAYMENT_SUCCEEDED` row exists; setup payments may legitimately have their own events. After `awaitRunStatus(batch.id(), RunStatus.SUCCEEDED)`, query by completed batch ID and assert one `RECONCILIATION_COMPLETED` payload contains the exact `batchId`, `runId`, `matchedRows`, and `differenceRows`.

- [ ] **Step 2: Run both module tests and verify they fail**

```bash
./mvnw -Dtest=TopUpModuleTest,ReconciliationModuleTest,ModularityTest test
```

Expected: failure because no business transaction appends events.

- [ ] **Step 3: Append payment success in the existing transaction**

Inject `OutboxApi` into `PaymentProcessor`. Immediately after the existing success audit, append:

```java
outboxApi.append(new OutboxCommand(
        EventType.PAYMENT_SUCCEEDED,
        "PAYMENT",
        payment.id().toString(),
        1,
        Map.of(
                "paymentType", payment.paymentType(),
                "amountCents", payment.amountCents(),
                "channelReference", payment.channelReference()),
        payment.occurredAt()));
```

Do not append from `fail()`. Add `messaging` to the payments module allowed dependencies.

- [ ] **Step 4: Append reconciliation completion in the existing transaction**

Inject `OutboxApi` into `ReconciliationStore`. After success audit and before returning, append:

```java
outboxApi.append(new OutboxCommand(
        EventType.RECONCILIATION_COMPLETED,
        "RECONCILIATION_BATCH",
        batch.id().toString(),
        1,
        Map.of(
                "batchId", batch.id().toString(),
                "runId", run.id().toString(),
                "matchedRows", matchedRows,
                "differenceRows", differenceRows),
        now));
```

Add `messaging` to the reconciliation module allowed dependencies. Do not emit from failure or recovery-audit paths.

- [ ] **Step 5: Run transaction tests and all module-boundary tests**

Run the command from Step 2. Expected: PASS; duplicate idempotent payment requests still leave one event because the second call does not reprocess a succeeded instruction.

- [ ] **Step 6: Commit business event production**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/payments \
  src/main/java/io/github/user32694/ledgerplatform/reconciliation \
  src/test/java/io/github/user32694/ledgerplatform/payments/TopUpModuleTest.java \
  src/test/java/io/github/user32694/ledgerplatform/reconciliation/ReconciliationModuleTest.java \
  src/test/java/io/github/user32694/ledgerplatform/ModularityTest.java
git commit -m "feat: 原子记录支付与对账成功事件"
```

### Task 6: Implement idempotent notification consumption

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/NotificationView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/NotificationsApi.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationEntity.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationRepository.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/ConsumedMessageStore.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationService.java`
- Create: `src/test/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationServiceTest.java`

- [ ] **Step 1: Write failing idempotency and rollback tests**

Create a valid payment envelope, consume it twice, and assert one row in each table. Consume a `schemaVersion == 2` envelope and assert an exception plus zero new `consumed_message` rows:

```java
service.consume(envelope);
service.consume(envelope);

assertThat(notificationsApi.findRecent(100)).singleElement()
        .satisfies(notification -> {
            assertThat(notification.eventId()).isEqualTo(envelope.eventId());
            assertThat(notification.title()).isEqualTo("资金操作成功");
            assertThat(notification.content()).contains("转账").contains("100.00");
        });
assertThat(count("notification.consumed_message")).isEqualTo(1);
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./mvnw -Dtest=NotificationServiceTest test
```

Expected: compilation failure because notification types do not exist.

- [ ] **Step 3: Implement the database idempotency barrier**

Use one JDBC statement and return whether this call owns processing:

```java
boolean claim(EventEnvelope event, Instant consumedAt) {
    return jdbcTemplate.update("""
            INSERT INTO notification.consumed_message
                (event_id, queue_name, event_type, consumed_at)
            VALUES (?, 'notification.events.v1', ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """, event.eventId(), event.eventType().name(), Timestamp.from(consumedAt)) == 1;
}
```

- [ ] **Step 4: Implement one transactional consumer service**

`consume()` validates schema version 1 and required Payload fields before claiming. Under one `@Transactional`, return on duplicate; otherwise format controlled Chinese content and save the notification. Payment cents use `BigDecimal.valueOf(cents, 2)`. Reconciliation content includes matched and difference row counts. Unknown fields are ignored, but missing or wrong-typed required fields throw `IllegalArgumentException` and roll back the claim.

Implement `findRecent(1..100)` and idempotent `markRead(UUID)` through `NotificationsApi`; a missing notification raises `IllegalArgumentException`.

- [ ] **Step 5: Run notification, migration, and modularity tests**

```bash
./mvnw -Dtest=NotificationServiceTest,MigrationIntegrationTest,ModularityTest test
```

Expected: PASS and no RabbitMQ connection attempt.

- [ ] **Step 6: Commit idempotent consumption**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/notifications \
  src/test/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationServiceTest.java
git commit -m "feat: 实现幂等站内通知消费"
```

### Task 7: Add listener retry, DLQ, and real RabbitMQ integration tests

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitMessagingConfiguration.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationMessageListener.java`
- Create: `src/test/resources/application-messaging-integration.yml`
- Create: `src/test/java/io/github/user32694/ledgerplatform/messaging/MessagingRabbitIT.java`

- [ ] **Step 1: Write a failing end-to-end RabbitMQ integration test**

Add `org.springframework.retry:spring-retry`, then configure a Failsafe `messaging-integration` Maven profile for `**/MessagingRabbitIT.java`. Create `application-messaging-integration.yml` so the profile reverses the default test isolation:

```yaml
spring:
  rabbitmq:
    dynamic: true
    listener:
      simple:
        auto-startup: true
app:
  messaging:
    publisher-enabled: true
```

Annotate the integration test with `@ActiveProfiles({"test", "messaging-integration"})`, purge main/DLQ queues before each method, and clear messaging/notification tables. Cover:

```java
UUID eventId = outboxApi.append(validPaymentCommand());

await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
    assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
    assertThat(notificationsApi.findRecent(100))
            .extracting(NotificationView::eventId)
            .contains(eventId);
});
```

Publish the same serialized envelope twice and assert one notification. Publish `{}` with routing key `payment.succeeded.v1` and await `notification.events.v1.dlq` ready count 1.

- [ ] **Step 2: Start local dependencies and verify the test fails**

```bash
docker compose up -d db rabbitmq
./mvnw -Pmessaging-integration -Dit.test=MessagingRabbitIT verify
```

Expected: failure because the listener and RabbitMQ service configuration are not complete. If the current Compose does not yet define `rabbitmq`, run `docker run --rm -d --name ledger-rabbit-plan -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=ledger_app -e RABBITMQ_DEFAULT_PASS=ledger_app rabbitmq:4-management` for this task only, then stop it with `docker stop ledger-rabbit-plan` after Step 5.

- [ ] **Step 3: Deserialize inside the listener and delegate**

Take Spring AMQP `Message` rather than a pre-converted POJO so malformed JSON enters the retry path:

```java
@RabbitListener(
        queues = RabbitTopology.NOTIFICATION_QUEUE,
        containerFactory = "notificationListenerContainerFactory")
void receive(Message message) throws IOException {
    EventEnvelope envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
    notificationService.consume(envelope);
}
```

- [ ] **Step 4: Configure exactly three attempts and dead-letter rejection**

Create a dedicated `SimpleRabbitListenerContainerFactory` with auto acknowledgement and a stateless interceptor:

```java
RetryOperationsInterceptor retryAdvice = RetryInterceptorBuilder.stateless()
        .maxAttempts(3)
        .backOffOptions(1_000, 2.0, 2_000)
        .recoverer(new RejectAndDontRequeueRecoverer())
        .build();
factory.setAdviceChain(retryAdvice);
factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
factory.setDefaultRequeueRejected(false);
```

Set the main queue arguments `x-dead-letter-exchange=ledger.events.dlx` and `x-dead-letter-routing-key=notification.dead.v1`; bind the durable DLQ to that key.

- [ ] **Step 5: Run the real Broker tests**

```bash
./mvnw -Pmessaging-integration -Dit.test=MessagingRabbitIT verify
```

Expected: PASS for confirmed delivery, duplicate idempotency, and malformed-message DLQ routing. Assert the test duration is at least about 3 seconds for the malformed message so it cannot pass by immediate rejection without retries.

- [ ] **Step 6: Commit listener reliability**

```bash
git add pom.xml src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitMessagingConfiguration.java \
  src/main/java/io/github/user32694/ledgerplatform/notifications/internal/NotificationMessageListener.java \
  src/test/resources/application-messaging-integration.yml \
  src/test/java/io/github/user32694/ledgerplatform/messaging/MessagingRabbitIT.java
git commit -m "test: 验证消息重试去重与死信流程"
```

### Task 8: Add Chinese notification and messaging operations pages

**Files:**
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxEventView.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/OutboxSummary.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/QueueDepths.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/MessagingOperationsApi.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxEventRepository.java`
- Modify: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/OutboxService.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/internal/RabbitQueueProbe.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/messaging/web/MessagingWebController.java`
- Create: `src/main/java/io/github/user32694/ledgerplatform/notifications/web/NotificationWebController.java`
- Create: `src/main/resources/templates/admin/messaging.html`
- Create: `src/main/resources/templates/admin/notifications.html`
- Modify: `src/main/resources/templates/admin/layout.html`
- Modify: `src/main/resources/static/css/admin.css`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java`

- [ ] **Step 1: Write failing MockMvc page and CSRF tests**

Verify authenticated GET responses contain Chinese labels and navigation, unauthenticated requests redirect to login, mark-read and retry reject missing CSRF, and valid POST redirects:

```java
mockMvc.perform(get("/admin/messaging").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/messaging"))
        .andExpect(content().string(containsString("消息运维")))
        .andExpect(content().string(containsString("待投递")))
        .andExpect(content().string(containsString("死信队列")));

mockMvc.perform(post("/admin/messaging/{id}/retry", failedEventId)
        .with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
```

- [ ] **Step 2: Run the web test and verify it fails**

```bash
./mvnw -Dtest=AdminWebTest test
```

Expected: 404 for the new routes.

- [ ] **Step 3: Implement operational APIs and queue probing**

`MessagingOperationsApi` exposes exactly:

```java
OutboxSummary summary();
List<OutboxEventView> findRecent(int limit);
QueueDepths queueDepths();
void retryFailed(UUID eventId);
```

Use repository count queries and a 100-row maximum. `RabbitQueueProbe` calls `RabbitAdmin.getQueueInfo()` for the main and DLQ names. Catch `AmqpException` and return `new QueueDepths(false, 0, 0)`; do not hide database errors. Delegate retry to the already-tested `OutboxStore.retryFailed()`.

- [ ] **Step 4: Implement controllers and templates**

Map status/type/payment labels in controllers, keeping enum and route names English. POST handlers are:

```java
@PostMapping("/admin/notifications/{id}/read")
String markRead(@PathVariable UUID id) {
    notificationsApi.markRead(id);
    return "redirect:/admin/notifications";
}

@PostMapping("/admin/messaging/{id}/retry")
String retry(@PathVariable UUID id, RedirectAttributes redirect) {
    messagingApi.retryFailed(id);
    redirect.addFlashAttribute("message", "事件已重新加入投递队列");
    return "redirect:/admin/messaging";
}
```

Use existing `.metrics`, `.table-section`, `.status`, `.button`, and responsive table styles. Add only selectors needed for unread emphasis and queue availability; do not restyle other pages. Templates must use escaped `th:text` for content and regular POST forms so existing CSRF integration applies.

- [ ] **Step 5: Run web, security, and responsive CSS tests**

```bash
./mvnw -Dtest=AdminWebTest,AdminResponsiveCssTest test
```

Expected: PASS at desktop/mobile assertions, including long IDs and errors wrapping inside their cells.

- [ ] **Step 6: Commit management pages**

```bash
git add src/main/java/io/github/user32694/ledgerplatform/messaging \
  src/main/java/io/github/user32694/ledgerplatform/notifications/web \
  src/main/resources/templates/admin src/main/resources/static/css/admin.css \
  src/test/java/io/github/user32694/ledgerplatform/AdminWebTest.java
git commit -m "feat: 增加中文通知与消息运维页面"
```

### Task 9: Add portable application, PostgreSQL, and RabbitMQ Compose startup

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Modify: `compose.yaml`
- Modify: `.env.example`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

- [ ] **Step 1: Write failing portability assertions**

Extend `portableRuntimeDocumentationExists` to include `Dockerfile` and `.dockerignore`. Add a test that Compose contains `app`, `db`, `rabbitmq`, health checks, port `15672`, and no literal production password:

```java
String compose = Files.readString(Path.of("compose.yaml"));
assertThat(compose)
        .contains("app:", "db:", "rabbitmq:", "15672:15672")
        .contains("condition: service_healthy")
        .doesNotContain("demo-password-2026");
```

- [ ] **Step 2: Run documentation tests and verify they fail**

```bash
./mvnw -Dtest=DocumentationTest test
```

Expected: failure because container files and services are missing.

- [ ] **Step 3: Add a Java 17 multi-stage image**

Use Maven Wrapper in the build stage and a non-root runtime user:

```dockerfile
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 ledger
WORKDIR /app
COPY --from=build /workspace/target/ledger-reconciliation-platform-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Exclude `.git`, `.worktrees`, `target`, `.env`, dumps, and logs from build context.

- [ ] **Step 4: Expand Compose and environment template**

Add durable `rabbitmq-data`, RabbitMQ management health check, and an `app` service that maps environment variables to service hostnames. Keep `${...:?}` guards for database, RabbitMQ, and admin passwords. `app` depends on healthy `db` and `rabbitmq`; use the `curl` installed by the Dockerfile:

```yaml
healthcheck:
  test: ["CMD", "curl", "--fail", "--silent", "http://localhost:8080/actuator/health"]
  interval: 10s
  timeout: 5s
  retries: 12
```

Required container URLs are `jdbc:postgresql://db:5432/ledger_platform` and RabbitMQ host `rabbitmq`. Preserve host ports 5432, 5672, 15672, and 8080.

- [ ] **Step 5: Validate Compose without starting services**

```bash
docker compose --env-file .env.example config --quiet
./mvnw -Dtest=DocumentationTest test
```

Expected: both commands exit 0 and rendered Compose contains exactly three services.

- [ ] **Step 6: Commit portable runtime**

```bash
git add Dockerfile .dockerignore compose.yaml .env.example \
  src/main/resources/application.yml \
  src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java
git commit -m "feat: 增加账本平台三服务一键启动"
```

### Task 10: Complete CI, manuals, failure demonstrations, and verification

**Files:**
- Modify: `.github/workflows/build.yml`
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `docs/MIGRATION.md`
- Modify: `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

- [ ] **Step 1: Write failing documentation assertions**

Add one test requiring all agreed operational terms:

```java
assertThat(readme)
        .contains("Transactional Outbox", "RabbitMQ", "at-least-once", "幂等消费");
assertThat(guide)
        .contains("/admin/notifications", "/admin/messaging")
        .contains("notification.events.v1.dlq")
        .contains("docker compose up --build")
        .contains("publisher confirm")
        .contains("不会替代 Spring Batch");
assertThat(migrationGuide)
        .contains("V16__add_outbox_and_notifications.sql")
        .contains("RabbitMQ 4")
        .contains("15672");
```

- [ ] **Step 2: Run documentation tests and verify they fail**

```bash
./mvnw -Dtest=DocumentationTest test
```

Expected: failure because the new runtime and recovery workflow are undocumented.

- [ ] **Step 3: Write operator-focused documentation**

Document both supported startup modes:

```bash
cp .env.example .env
docker compose up --build
```

and host Java with Compose dependencies. Include login URL `http://localhost:8080/login`, Rabbit management URL `http://localhost:15672`, synthetic payment/reconciliation demonstration, notification verification, Broker-stop/Outbox-recovery procedure, malformed-message/DLQ procedure, failed Outbox manual retry, shutdown, clean-new-machine migration, and optional PostgreSQL data migration. State explicitly that delivery is at-least-once, consumers are idempotent, and RabbitMQ does not replace Spring Batch.

- [ ] **Step 4: Add RabbitMQ to GitHub Actions**

Add a service with health checks and connection environment:

```yaml
rabbitmq:
  image: rabbitmq:4-management
  env:
    RABBITMQ_DEFAULT_USER: ledger_app
    RABBITMQ_DEFAULT_PASS: ledger_app
  ports:
    - 5672:5672
    - 15672:15672
  options: >-
    --health-cmd "rabbitmq-diagnostics -q ping"
    --health-interval 5s
    --health-timeout 5s
    --health-retries 12
```

Set `RABBITMQ_HOST`, `RABBITMQ_USERNAME`, and `RABBITMQ_PASSWORD` in CI, and change Verify to `./mvnw --batch-mode -Pmessaging-integration verify`.

- [ ] **Step 5: Run the complete local verification matrix**

```bash
./mvnw clean verify
./mvnw -Pmessaging-integration verify
docker compose --env-file .env.example config --quiet
```

Expected: all Maven tests pass; the integration run proves confirmed publishing, idempotency, and DLQ routing; Compose validation exits 0.

- [ ] **Step 6: Run the three-service smoke test**

```bash
docker compose --env-file .env.example up --build -d
curl --fail http://localhost:8080/actuator/health
docker compose --env-file .env.example ps
docker compose --env-file .env.example down
```

Expected: health returns HTTP 200 with `"status":"UP"`; `ps` shows `app`, `db`, and `rabbitmq` healthy before shutdown. Do not add `--volumes`, so user data remains recoverable.

- [ ] **Step 7: Re-run self-review checks**

```bash
git diff --check main...HEAD
! git grep -n -E 'T[B]D|T[O]DO|FIX[M]E|demo-password-2026' -- \
  README.md docs src/main .github compose.yaml Dockerfile .env.example
git status --short
```

Expected: no whitespace errors, no unfinished documentation, no committed real credentials, and only intended files changed. The test datasource may contain localhost only in `application-test.yml`; production container configuration must use environment variables.

- [ ] **Step 8: Commit CI and manuals**

```bash
git add .github/workflows/build.yml README.md docs/USER_GUIDE.md docs/MIGRATION.md \
  src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java
git commit -m "docs: 完善可靠消息运行验证与迁移手册"
```

- [ ] **Step 9: Inspect final history and prepare review**

```bash
git status --short --branch
git log --oneline main..HEAD
```

Expected: clean `feature/transactional-outbox-rabbitmq` worktree and a reviewable sequence of focused commits. Do not merge or push until the implementation review reports no blocking findings.
