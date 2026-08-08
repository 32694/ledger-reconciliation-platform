package io.github.user32694.ledgerplatform.reconciliation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_statement_entry", schema = "reconciliation")
class ChannelStatementEntryEntity {
    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "channel_transaction_id", nullable = false, unique = true, length = 64)
    private String channelTransactionId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ChannelStatementEntryEntity() {}

    static ChannelStatementEntryEntity from(UUID batchId, ParsedStatement.Entry entry) {
        var entity = new ChannelStatementEntryEntity();
        entity.id = UUID.randomUUID();
        entity.batchId = batchId;
        entity.lineNumber = entry.lineNumber();
        entity.channelTransactionId = entry.channelTransactionId();
        entity.amountCents = entry.amountCents();
        entity.occurredAt = entry.occurredAt();
        return entity;
    }
}
