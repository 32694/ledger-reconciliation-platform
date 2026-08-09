ALTER TABLE payments.payment_instruction
    ALTER COLUMN payment_type TYPE VARCHAR(24),
    ADD COLUMN original_payment_id UUID REFERENCES payments.payment_instruction(id),
    ADD COLUMN operation_reason VARCHAR(500);
ALTER TABLE payments.payment_instruction
    DROP CONSTRAINT payment_instruction_payment_type_check,
    DROP CONSTRAINT ck_payment_instruction_parties;
ALTER TABLE payments.payment_instruction
    ADD CONSTRAINT ck_payment_instruction_type CHECK (
        payment_type IN ('TOP_UP', 'TRANSFER', 'REFUND', 'REVERSAL')),
    ADD CONSTRAINT ck_payment_instruction_parties CHECK (
        (payment_type IN ('TOP_UP', 'REFUND') AND payer_account_id IS NULL)
        OR (payment_type IN ('TRANSFER', 'REVERSAL')
            AND payer_account_id IS NOT NULL AND payer_account_id <> payee_account_id)),
    ADD CONSTRAINT ck_payment_instruction_reverse_fields CHECK (
        (payment_type IN ('TOP_UP', 'TRANSFER')
            AND original_payment_id IS NULL AND operation_reason IS NULL)
        OR (payment_type IN ('REFUND', 'REVERSAL')
            AND original_payment_id IS NOT NULL AND original_payment_id <> id
            AND operation_reason IS NOT NULL
            AND char_length(btrim(operation_reason)) BETWEEN 1 AND 500));
CREATE UNIQUE INDEX uk_payment_instruction_active_reverse
    ON payments.payment_instruction (original_payment_id)
    WHERE original_payment_id IS NOT NULL AND status IN ('PENDING', 'SUCCEEDED');
