package io.github.user32694.ledgerplatform.notifications;

import java.util.List;
import java.util.UUID;

public interface NotificationsApi {
    List<NotificationView> findRecent(int limit);

    void markRead(UUID id);
}
