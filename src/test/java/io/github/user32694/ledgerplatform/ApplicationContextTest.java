package io.github.user32694.ledgerplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "app.admin.username=test-admin",
    "app.admin.password=test-password"
})
@ActiveProfiles("test")
class ApplicationContextTest {
    @Test
    void startsApplicationContext() {}
}
