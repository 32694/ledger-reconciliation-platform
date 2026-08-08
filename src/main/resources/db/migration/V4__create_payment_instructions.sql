CREATE TABLE payments.payment_instruction (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    channel_reference VARCHAR(64) NOT NULL UNIQUE,
    payment_type VARCHAR(16) NOT NULL CHECK (payment_type IN ('TOP_UP', 'TRANSFER')),
    payer_account_id UUID REFERENCES accounts.customer_account(id),
    payee_account_id UUID NOT NULL REFERENCES accounts.customer_account(id),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'CNY'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_created_at ON payments.payment_instruction(created_at DESC);
