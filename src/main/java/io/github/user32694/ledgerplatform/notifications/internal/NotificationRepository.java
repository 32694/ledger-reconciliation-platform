package io.github.user32694.ledgerplatform.notifications.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    List<NotificationEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
