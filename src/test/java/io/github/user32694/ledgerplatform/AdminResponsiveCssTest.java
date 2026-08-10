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

    @Test
    void reconciliationProgressHasStableSizeAndMobileWrapping() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/admin.css"));

        assertThat(css)
                .contains(".reconciliation-run-status { min-height: 154px;")
                .contains(".reconciliation-progress { min-width: 0;")
                .contains(".reconciliation-run-actions { flex-wrap: wrap;")
                .contains(".reconciliation-progress { grid-template-columns: minmax(0, 1fr) auto; }");
    }

    @Test
    void messagingTablesWrapLongOperationalValuesAndEmphasizeUnreadRows() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/admin.css"));

        assertThat(css)
                .contains(".wrap-cell { white-space: normal; overflow-wrap: anywhere;")
                .contains(".notification-unread")
                .contains(".queue-unavailable");
    }
}
