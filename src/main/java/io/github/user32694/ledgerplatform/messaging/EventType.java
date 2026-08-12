package io.github.user32694.ledgerplatform.messaging;

/** 当前支持的业务事件及其 RabbitMQ routing key。 */
public enum EventType {
    PAYMENT_SUCCEEDED("payment.succeeded.v1"),
    RECONCILIATION_COMPLETED("reconciliation.completed.v1");

    private final String routingKey;

    EventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
