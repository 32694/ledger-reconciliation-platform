package io.github.user32694.ledgerplatform.messaging;

public record OutboxSummary(
        long pendingCount, long publishingCount, long publishedCount, long failedCount) {}
