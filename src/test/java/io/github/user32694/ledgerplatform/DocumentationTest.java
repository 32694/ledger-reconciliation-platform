package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
