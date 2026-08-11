package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class DocumentationTest {
    @ParameterizedTest
    @ValueSource(strings = {
        ".env.example",
        ".dockerignore",
        "Dockerfile",
        "compose.yaml",
        "LICENSE",
        "docs/USER_GUIDE.md",
        "docs/MIGRATION.md"
    })
    void portableRuntimeDocumentationExists(String relativePath) {
        assertThat(Files.isRegularFile(Path.of(relativePath)))
                .as("%s should be a regular file", relativePath)
                .isTrue();
    }

    @org.junit.jupiter.api.Test
    void documentsPortfolioShowcase() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        var screenshots = java.util.List.of(
                "docs/images/operations-overview.png",
                "docs/images/payment-detail-reversal.png",
                "docs/images/reconciliation-case.png",
                "docs/images/messaging-operations.png");

        for (String screenshot : screenshots) {
            Path screenshotPath = Path.of(screenshot);
            assertThat(Files.isRegularFile(screenshotPath))
                    .as("%s should be a regular file", screenshot)
                    .isTrue();
            assertThat(Files.size(screenshotPath))
                    .as("%s should not be empty", screenshot)
                    .isGreaterThan(0L);
            assertThat(readme).contains("(" + screenshot + ")");
        }

        assertThat(readme)
                .contains("https://github.com/32694/ledger-reconciliation-platform/actions/workflows/build.yml")
                .contains("## 界面预览", "## 系统架构", "## 三分钟演示")
                .contains("```mermaid")
                .contains("Transactional Outbox", "RabbitMQ", "Spring Batch", "at-least-once", "eventId")
                .contains("[用户手册](docs/USER_GUIDE.md)")
                .contains("[迁移手册](docs/MIGRATION.md)")
                .contains("[MIT License](LICENSE)");
    }

    @org.junit.jupiter.api.Test
    void composeDefinesPortableApplicationDatabaseAndRabbitMqServices() throws IOException {
        String compose = Files.readString(Path.of("compose.yaml"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String environment = Files.readString(Path.of(".env.example"));

        assertThat(compose)
                .contains("app:", "db:", "rabbitmq:", "15672:15672")
                .contains("condition: service_healthy")
                .contains("OUTBOX_PUBLISH_INTERVAL: ${OUTBOX_PUBLISH_INTERVAL:-PT1S}")
                .contains("DB_PASSWORD:?", "RABBITMQ_PASSWORD:?", "APP_ADMIN_PASSWORD:?")
                .doesNotContain("demo-password-2026");
        assertThat(application)
                .contains("publish-interval: ${OUTBOX_PUBLISH_INTERVAL:PT1S}")
                .contains("username: ${RABBITMQ_USERNAME}", "password: ${RABBITMQ_PASSWORD}")
                .doesNotContain("${RABBITMQ_USERNAME:", "${RABBITMQ_PASSWORD:");
        assertThat(environment).contains("OUTBOX_PUBLISH_INTERVAL=PT1S");
    }

    @org.junit.jupiter.api.Test
    void documentsReliableMessagingOperationsAndMigration() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));
        String migrationGuide = Files.readString(Path.of("docs/MIGRATION.md"));

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
    }

    @org.junit.jupiter.api.Test
    void documentsAutomaticReconciliationMilestone() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));

        assertThat(readme)
                .contains("`reconciliation`")
                .contains("自动对账")
                .doesNotContain("当前应用不能导入");
        assertThat(guide)
                .contains("channel_transaction_id,amount_cents,occurred_at")
                .contains("100,000")
                .contains("20 MB")
                .contains("Spring Batch")
                .contains("reconciliation-performance")
                .contains("匹配一致")
                .contains("金额不一致")
                .contains("仅渠道存在")
                .contains("仅内部存在")
                .contains("原支付和账本事实不会被修改");
    }

    @org.junit.jupiter.api.Test
    void documentsAccountTransferMilestone() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));

        assertThat(readme)
                .contains("账户转账")
                .contains("双重记账")
                .contains("并发")
                .doesNotContain("transfers, production channel integrations");
        assertThat(guide)
                .contains("/admin/payments/transfer")
                .contains("付款账户余额不足")
                .contains("`INSUFFICIENT_FUNDS`")
                .contains("不能出现负余额")
                .contains("10,000")
                .contains("2,500");
    }

    @org.junit.jupiter.api.Test
    void documentsReversePaymentAndAuditWorkflows() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));
        String migrationGuide = Files.readString(Path.of("docs/MIGRATION.md"));

        assertThat(readme)
                .contains("全额退款")
                .contains("全额冲正")
                .contains("审计日志")
                .contains("不可变");
        assertThat(guide)
                .contains("全额退款")
                .contains("全额冲正")
                .contains("INSUFFICIENT_FUNDS")
                .contains("审计日志")
                .contains("应用接口只追加")
                .contains("不提供修改或删除入口")
                .contains("数据库层未禁止直接修改")
                .contains("使用新幂等键重试")
                .contains("两份不可变 journal")
                .contains("唯一幂等键");
        assertThat(readme)
                .contains("应用接口只追加")
                .contains("不提供修改或删除入口")
                .doesNotContain("只追加、不可修改的审计事件");
        assertThat(migrationGuide)
                .contains("不包含禁止 `UPDATE` 或 `DELETE` 的数据库触发器");
    }

    @org.junit.jupiter.api.Test
    void documentsRestartableSpringBatchReconciliationWorkflow() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));

        assertThat(readme)
                .contains("异步对账")
                .contains("Spring Batch")
                .contains("默认规则")
                .contains("渠道规则")
                .contains("不可变")
                .contains("只记录运营结论")
                .contains("不会自动修改账本");
        assertThat(guide)
                .contains("`QUEUED`：等待执行")
                .contains("`RUNNING`：对账中")
                .contains("`SUCCEEDED`：已完成")
                .contains("`FAILED`：执行失败")
                .contains("HTMX")
                .contains("每 `2` 秒")
                .contains("导入成功后自动进入批次详情")
                .contains("查看历史批次")
                .contains("从检查点继续")
                .contains("新建尝试")
                .contains("规则版本")
                .contains("渠道")
                .contains("/admin/reconciliation/cases")
                .contains("`OPEN` -> `CLAIMED` -> `RESOLVED`")
                .contains("认领案件")
                .contains("取消认领")
                .contains("解决案件")
                .contains("`INTERNAL_CONFIRMED`：内部账务为准")
                .contains("`CHANNEL_CONFIRMED`：渠道账单为准")
                .contains("`IGNORED_TEST_DATA`：忽略测试数据")
                .contains("`OTHER`：其他")
                .contains("不可变时间线")
                .contains("审计日志")
                .contains("只记录运营结论")
                .contains("不会自动修改账本")
                .doesNotContain("应用进程内本地线程池")
                .doesNotContain("原任务不会续跑")
                .doesNotContain("导入成功后，在批次列表点击**查看详情**")
                .doesNotContain("**导入账单** -> **查看详情** -> **开始对账**");
    }

    @org.junit.jupiter.api.Test
    void documentsReconciliationOperationsMigrationAndRollbackBoundaries() throws IOException {
        String migrationGuide = Files.readString(Path.of("docs/MIGRATION.md"));

        assertThat(migrationGuide)
                .contains("JDK 17")
                .contains("PostgreSQL 17")
                .contains("Flyway")
                .contains("V12__add_reconciliation_operations.sql")
                .contains("V13__allow_claimed_reconciliation_status.sql")
                .contains("V14__add_reconciliation_rules_and_work_results.sql")
                .contains("V15__create_spring_batch_metadata.sql")
                .contains("旧约束")
                .contains("升级前备份")
                .contains("batch")
                .contains("检查点")
                .contains("Flyway 不提供自动回滚")
                .contains("升级后产生的运行历史、案件时间线和审计记录")
                .contains("不可回滚");
    }

    @org.junit.jupiter.api.Test
    void documentsDatabaseMigrationVersionsAndVerification() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String migrationGuide = Files.readString(Path.of("docs/MIGRATION.md"));

        assertThat(readme)
                .contains("JDK 17")
                .contains("PostgreSQL 17")
                .contains("./mvnw clean verify");
        assertThat(migrationGuide)
                .contains("V9__add_payment_refunds_and_reversals.sql")
                .contains("V10__create_audit_events.sql")
                .contains("V11__allow_reverse_journal_types.sql")
                .contains("PostgreSQL 17")
                .contains("JDK 17")
                .contains("./mvnw clean verify")
                .contains("V1-V8")
                .contains("不可直接修改已执行");
    }

    @org.junit.jupiter.api.Test
    void documentsPortableReconciliationRulesAndPerformanceVerification() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));
        String migrationGuide = Files.readString(Path.of("docs/MIGRATION.md"));

        for (String document : java.util.List.of(readme, guide, migrationGuide)) {
            assertThat(document)
                    .contains("SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_test")
                    .contains("SPRING_DATASOURCE_USERNAME=\"$DB_USERNAME\"")
                    .contains("SPRING_DATASOURCE_PASSWORD=\"$DB_PASSWORD\"")
                    .contains("leader election")
                    .contains("lease")
                    .contains("heartbeat")
                    .contains("应用自身不实现");
        }
        assertThat(readme).contains("-Preconciliation-performance");
        assertThat(guide)
                .contains("/admin/reconciliation/rules")
                .contains("保存草稿")
                .contains("发布")
                .contains("只使用已发布版本")
                .contains("### 6.1 管理对账规则")
                .contains("### 6.2 导入并运行对账")
                .contains("### 6.3 在异常工作台处理差异")
                .contains("### 6.4 演示流程")
                .contains("### 6.5 100,000 行演示")
                .contains("任一已启用渠道")
                .contains("支付宝、微信支付或银联")
                .contains("-Preconciliation-performance");
        assertThat(guide)
                .doesNotContain("### 6.2 在异常工作台处理差异")
                .doesNotContain("SYNTHETIC_CHANNEL");
    }

    @org.junit.jupiter.api.Test
    void documentsPosixEnvironmentLoadingAndPerformanceOutputNames() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String guide = Files.readString(Path.of("docs/USER_GUIDE.md"));
        String migrationGuide = Files.readString(Path.of("docs/MIGRATION.md"));

        for (String document : java.util.List.of(readme, guide, migrationGuide)) {
            assertThat(document)
                    .contains(". ./.env")
                    .doesNotContain("source .env");
        }
        for (String document : java.util.List.of(readme, guide)) {
            assertThat(document)
                    .contains("channelRowsPerSecond")
                    .contains("channelRows")
                    .contains("resultRows")
                    .doesNotContain("throughputRowsPerSecond");
        }
    }

    @org.junit.jupiter.api.Test
    void rejectsDirectoryAsReconciliationDemoOutput(@TempDir Path temporaryDirectory)
            throws IOException, InterruptedException {
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("directory-output"));
        var process = new ProcessBuilder(
                "scripts/generate-reconciliation-demo.sh", outputDirectory.toString(), "1")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isEqualTo(64);
        assertThat(output).contains("输出路径不能是目录");
        try (var generatedFiles = Files.list(outputDirectory)) {
            assertThat(generatedFiles).isEmpty();
        }
    }
}
