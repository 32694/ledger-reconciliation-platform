DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ledger.ledger_transaction'::regclass
          AND conname = 'ledger_transaction_business_reference_key'
    ) THEN
        ALTER TABLE ledger.ledger_transaction
            RENAME CONSTRAINT ledger_transaction_business_reference_key
            TO uk_ledger_transaction_business_reference;
    END IF;
END
$$;
