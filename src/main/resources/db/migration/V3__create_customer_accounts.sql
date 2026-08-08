CREATE TABLE accounts.customer_account (
    id UUID PRIMARY KEY,
    account_number VARCHAR(32) NOT NULL UNIQUE,
    owner_name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    currency CHAR(3) NOT NULL CHECK (currency = 'CNY'),
    ledger_account_id UUID NOT NULL UNIQUE REFERENCES ledger.ledger_account(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
