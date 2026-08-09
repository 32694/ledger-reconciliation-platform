ALTER TABLE ledger.ledger_transaction
    DROP CONSTRAINT ledger_transaction_transaction_type_check;

ALTER TABLE ledger.ledger_transaction
    ADD CONSTRAINT ck_ledger_transaction_type CHECK (
        transaction_type IN ('TOP_UP', 'TRANSFER', 'REFUND', 'REVERSAL'));
