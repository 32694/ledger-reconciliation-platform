CREATE TABLE ledger.ledger_account (
    id UUID PRIMARY KEY,
    owner_ref VARCHAR(64) NOT NULL UNIQUE,
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY')),
    currency CHAR(3) NOT NULL CHECK (currency = 'CNY'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger.ledger_transaction (
    id UUID PRIMARY KEY,
    business_reference VARCHAR(128) NOT NULL UNIQUE,
    transaction_type VARCHAR(32) NOT NULL CHECK (transaction_type IN ('TOP_UP', 'TRANSFER')),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger.ledger_entry (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger.ledger_transaction(id),
    ledger_account_id UUID NOT NULL REFERENCES ledger.ledger_account(id),
    side VARCHAR(8) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ledger_entry_transaction ON ledger.ledger_entry(transaction_id);
CREATE INDEX idx_ledger_entry_account ON ledger.ledger_entry(ledger_account_id);
