CREATE SCHEMA reconciliation;

CREATE TABLE reconciliation.reconciliation_batch (
    id UUID PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL CHECK (source_type = 'SYNTHETIC_CHANNEL'),
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL UNIQUE,
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL CHECK (status IN
        ('IMPORTED', 'RUNNING', 'COMPLETED', 'IMPORT_FAILED', 'RECONCILIATION_FAILED')),
    total_rows INTEGER NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    matched_rows INTEGER NOT NULL DEFAULT 0 CHECK (matched_rows >= 0),
    difference_rows INTEGER NOT NULL DEFAULT 0 CHECK (difference_rows >= 0),
    error_message VARCHAR(2000),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_reconciliation_batch_period CHECK (
        (status = 'IMPORT_FAILED' AND period_start IS NULL AND period_end IS NULL)
        OR (status <> 'IMPORT_FAILED' AND period_start IS NOT NULL
            AND period_end IS NOT NULL AND period_start <= period_end))
);

CREATE TABLE reconciliation.channel_statement_entry (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    line_number INTEGER NOT NULL CHECK (line_number >= 2),
    channel_transaction_id VARCHAR(64) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (batch_id, line_number)
);

CREATE TABLE reconciliation.reconciliation_result (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    statement_entry_id UUID REFERENCES reconciliation.channel_statement_entry(id),
    payment_id UUID,
    result_type VARCHAR(32) NOT NULL CHECK (result_type IN
        ('MATCHED', 'AMOUNT_MISMATCH', 'CHANNEL_ONLY', 'INTERNAL_ONLY')),
    resolution_status VARCHAR(16) NOT NULL CHECK (resolution_status IN
        ('NOT_REQUIRED', 'OPEN', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (batch_id, statement_entry_id),
    UNIQUE (batch_id, payment_id),
    CONSTRAINT ck_reconciliation_result_refs CHECK (
        (result_type = 'INTERNAL_ONLY' AND statement_entry_id IS NULL AND payment_id IS NOT NULL)
        OR (result_type = 'CHANNEL_ONLY' AND statement_entry_id IS NOT NULL AND payment_id IS NULL)
        OR (result_type IN ('MATCHED', 'AMOUNT_MISMATCH')
            AND statement_entry_id IS NOT NULL AND payment_id IS NOT NULL)),
    CONSTRAINT ck_reconciliation_result_resolution CHECK (
        (result_type = 'MATCHED' AND resolution_status = 'NOT_REQUIRED')
        OR (result_type <> 'MATCHED' AND resolution_status IN ('OPEN', 'RESOLVED')))
);

CREATE TABLE reconciliation.reconciliation_resolution (
    id UUID PRIMARY KEY,
    result_id UUID NOT NULL UNIQUE REFERENCES reconciliation.reconciliation_result(id),
    action VARCHAR(32) NOT NULL CHECK (action = 'RESOLVE'),
    note VARCHAR(2000) NOT NULL CHECK (length(trim(note)) > 0),
    operator VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reconciliation_batch_created
    ON reconciliation.reconciliation_batch(created_at DESC, id DESC);
CREATE INDEX idx_statement_entry_batch
    ON reconciliation.channel_statement_entry(batch_id, occurred_at, channel_transaction_id);
CREATE INDEX idx_reconciliation_result_batch
    ON reconciliation.reconciliation_result(batch_id, result_type, resolution_status);
