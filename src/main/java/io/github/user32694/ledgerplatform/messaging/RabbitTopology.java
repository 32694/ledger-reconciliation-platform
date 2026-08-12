package io.github.user32694.ledgerplatform.messaging;

/** 集中定义 exchange、queue 和 DLQ 名称，避免生产者和消费者写出不一致字符串。 */
public final class RabbitTopology {
    public static final String EVENT_EXCHANGE = "ledger.events";
    public static final String NOTIFICATION_QUEUE = "notification.events.v1";
    public static final String NOTIFICATION_DLQ = "notification.events.v1.dlq";
    public static final String DEAD_LETTER_EXCHANGE = "ledger.events.dlx";

    private RabbitTopology() {}
}
