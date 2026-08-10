package io.github.user32694.ledgerplatform.messaging;

public final class RabbitTopology {
    public static final String EVENT_EXCHANGE = "ledger.events";
    public static final String NOTIFICATION_QUEUE = "notification.events.v1";
    public static final String NOTIFICATION_DLQ = "notification.events.v1.dlq";
    public static final String DEAD_LETTER_EXCHANGE = "ledger.events.dlx";

    private RabbitTopology() {}
}
