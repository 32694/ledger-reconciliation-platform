CREATE SCHEMA IF NOT EXISTS audit;
CREATE TABLE audit.audit_event (
    id UUID PRIMARY KEY,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    summary VARCHAR(500) NOT NULL,
    correlation_reference VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_event_occurred_at
    ON audit.audit_event (occurred_at DESC, id DESC);
