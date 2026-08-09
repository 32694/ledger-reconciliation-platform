package io.github.user32694.ledgerplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminResponsiveCssTest {
    @Test
    void mobilePageHeadingStacksActionsBelowTitle() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/admin.css"));

        assertThat(css)
                .contains(".page-heading { align-items: stretch; flex-direction: column; }")
                .contains(".page-heading .actions { justify-content: flex-start; }");
    }
}
