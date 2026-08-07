package io.github.user32694.ledgerplatform.identity.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String configuredUsername;
    private final String configuredPassword;

    public AdminBootstrap(
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.username:}") String configuredUsername,
            @Value("${app.admin.password:}") String configuredPassword) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (configuredUsername == null || configuredUsername.isBlank()) {
            throw new IllegalStateException("app.admin.username is required");
        }
        if (configuredPassword == null || configuredPassword.isBlank()) {
            throw new IllegalStateException("app.admin.password is required");
        }

        String username = configuredUsername.strip();
        if (username.length() > 80) {
            throw new IllegalStateException("app.admin.username must not exceed 80 characters");
        }
        repository.insertIfAbsent(
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(configuredPassword),
                Instant.now());
    }
}
