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
}
