package io.github.user32694.ledgerplatform.notifications.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    List<NotificationEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Modifying
    @Query("""
            UPDATE NotificationEntity notification
            SET notification.readAt = :readAt
            WHERE notification.id = :id AND notification.readAt IS NULL
            """)
    int markReadIfUnread(@Param("id") UUID id, @Param("readAt") Instant readAt);
}
