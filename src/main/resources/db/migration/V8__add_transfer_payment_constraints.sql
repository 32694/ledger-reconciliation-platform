ALTER TABLE payments.payment_instruction
    ADD CONSTRAINT ck_payment_instruction_parties CHECK (
        (payment_type = 'TOP_UP' AND payer_account_id IS NULL)
        OR
        (payment_type = 'TRANSFER'
            AND payer_account_id IS NOT NULL
            AND payer_account_id <> payee_account_id)
    );
