package io.github.user32694.ledgerplatform.messaging;

import java.util.UUID;

public interface OutboxApi {
    UUID append(OutboxCommand command);
}
