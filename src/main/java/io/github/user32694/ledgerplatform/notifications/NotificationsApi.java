package io.github.user32694.ledgerplatform.notifications;

import java.util.List;
import java.util.UUID;

/** 站内通知模块公开端口。 */
public interface NotificationsApi {
    /** 查询最近通知。 */
    List<NotificationView> findRecent(int limit);

    /** 将指定未读通知标记为已读。 */
    void markRead(UUID id);
}
