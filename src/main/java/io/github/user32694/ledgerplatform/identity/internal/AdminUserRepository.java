package io.github.user32694.ledgerplatform.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AdminUserRepository extends JpaRepository<AdminUserEntity, UUID> {
    Optional<AdminUserEntity> findByUsername(String username);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO identity.admin_user
                (id, username, password_hash, enabled, created_at)
            VALUES
                (:id, :username, :passwordHash, TRUE, :createdAt)
            ON CONFLICT (username) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("createdAt") Instant createdAt);
}
