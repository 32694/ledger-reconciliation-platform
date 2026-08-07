package io.github.user32694.ledgerplatform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

@SpringBootTest(properties = {
    "app.admin.username=admin",
    "app.admin.password=test-password"
})
@ActiveProfiles("test")
@Sql(statements = {
    "DELETE FROM identity.admin_user WHERE username LIKE 'bootstrap-test-%'"
}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
    "DELETE FROM identity.admin_user WHERE username LIKE 'bootstrap-test-%'"
}, executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AdminBootstrapTest {
    @Autowired AdminUserRepository repository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void initializesAdministratorOnceAcrossConcurrentInstances() throws Exception {
        String username = "bootstrap-test-concurrent";
        String password = "concurrent-password";
        var barrier = new CyclicBarrier(2);
        AdminUserRepository coordinatedRepository =
                mock(AdminUserRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
                    var existing = repository.findByUsername(username);
                    barrier.await(5, TimeUnit.SECONDS);
                    return existing;
                })
                .when(coordinatedRepository)
                .findByUsername(username);
        var firstBootstrap = new AdminBootstrap(
                coordinatedRepository, passwordEncoder, username, password);
        var secondBootstrap = new AdminBootstrap(
                coordinatedRepository, passwordEncoder, username, password);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                firstBootstrap.run(null);
                return null;
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                secondBootstrap.run(null);
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThatCode(() -> {
                        first.get(10, TimeUnit.SECONDS);
                        second.get(10, TimeUnit.SECONDS);
                    })
                    .doesNotThrowAnyException();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        var stored = repository.findByUsername(username).orElseThrow();
        assertThat(repository.findAll())
                .filteredOn(admin -> admin.username().equals(username))
                .hasSize(1);
        assertThat(stored.passwordHash()).isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, stored.passwordHash())).isTrue();
    }

    @Test
    void keepsExistingAdministratorPassword() {
        String username = "bootstrap-test-existing";
        String originalHash = passwordEncoder.encode("original-password");
        repository.saveAndFlush(new AdminUserEntity(
                UUID.randomUUID(), username, originalHash, true, Instant.now()));

        new AdminBootstrap(repository, passwordEncoder, username, "replacement-password").run(null);

        assertThat(repository.findByUsername(username).orElseThrow().passwordHash())
                .isEqualTo(originalHash);
    }
}
