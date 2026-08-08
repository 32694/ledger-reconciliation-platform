DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a
          ON a.attrelid = c.conrelid
         AND a.attname = 'business_reference'
        WHERE c.conrelid = 'ledger.ledger_transaction'::regclass
          AND c.conname = 'uk_ledger_transaction_business_reference'
          AND c.contype = 'u'
          AND c.conkey = ARRAY[a.attnum]
    ) THEN
        NULL;
    ELSIF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a
          ON a.attrelid = c.conrelid
         AND a.attname = 'business_reference'
        WHERE c.conrelid = 'ledger.ledger_transaction'::regclass
          AND c.conname = 'ledger_transaction_business_reference_key'
          AND c.contype = 'u'
          AND c.conkey = ARRAY[a.attnum]
    ) THEN
        ALTER TABLE ledger.ledger_transaction
            RENAME CONSTRAINT ledger_transaction_business_reference_key
            TO uk_ledger_transaction_business_reference;
    ELSE
        RAISE EXCEPTION 'Expected a unique business_reference constraint on ledger.ledger_transaction';
    END IF;
END
$$;
