package io.github.user32694.ledgerplatform.messaging;

public record QueueDepths(boolean available, int mainQueueCount, int deadLetterQueueCount) {}
