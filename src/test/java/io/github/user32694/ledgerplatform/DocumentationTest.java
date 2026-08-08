package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentationTest {
    @ParameterizedTest
    @ValueSource(strings = {
        ".env.example",
        "compose.yaml",
        "docs/USER_GUIDE.md",
        "docs/MIGRATION.md"
    })
    void portableRuntimeDocumentationExists(String relativePath) {
        assertThat(Files.isRegularFile(Path.of(relativePath)))
                .as("%s should be a regular file", relativePath)
                .isTrue();
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
                .contains("10,000")
                .contains("2 MB")
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
                .contains("使用新幂等键重试")
                .contains("两份不可变 journal")
                .contains("唯一幂等键");
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
}
