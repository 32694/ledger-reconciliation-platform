ALTER TABLE reconciliation.reconciliation_result
    DROP CONSTRAINT IF EXISTS reconciliation_result_resolution_status_check,
    ADD CONSTRAINT ck_reconciliation_result_status CHECK (
        resolution_status IN ('NOT_REQUIRED', 'OPEN', 'CLAIMED', 'RESOLVED'));
