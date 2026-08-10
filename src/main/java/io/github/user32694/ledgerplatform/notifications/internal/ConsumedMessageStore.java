package io.github.user32694.ledgerplatform.notifications.internal;

import io.github.user32694.ledgerplatform.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ConsumedMessageStore {
    private final JdbcTemplate jdbcTemplate;

    ConsumedMessageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    boolean claim(EventEnvelope event, Instant consumedAt) {
        return jdbcTemplate.update("""
                INSERT INTO notification.consumed_message
                    (event_id, queue_name, event_type, consumed_at)
                VALUES (?, 'notification.events.v1', ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.eventType().name(), Timestamp.from(consumedAt)) == 1;
    }
}
