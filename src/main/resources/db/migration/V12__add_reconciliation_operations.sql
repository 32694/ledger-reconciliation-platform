CREATE TABLE reconciliation.reconciliation_run (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    requested_by VARCHAR(128) NOT NULL CHECK (length(trim(requested_by)) > 0),
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    matched_rows INTEGER NOT NULL DEFAULT 0 CHECK (matched_rows >= 0),
    difference_rows INTEGER NOT NULL DEFAULT 0 CHECK (difference_rows >= 0),
    error_message VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (batch_id, attempt_number),
    CONSTRAINT ck_reconciliation_run_state CHECK (
        (status = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL AND error_message IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL AND error_message IS NULL)
        OR (status = 'SUCCEEDED' AND started_at IS NOT NULL AND completed_at IS NOT NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_message IS NOT NULL
            AND length(trim(error_message)) > 0))
);

CREATE UNIQUE INDEX uq_reconciliation_run_active
    ON reconciliation.reconciliation_run(batch_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_reconciliation_run_batch
    ON reconciliation.reconciliation_run(batch_id, attempt_number DESC);

ALTER TABLE reconciliation.reconciliation_result
    ADD COLUMN assigned_to VARCHAR(128),
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE reconciliation.reconciliation_resolution
    ADD COLUMN resolution_code VARCHAR(32);

UPDATE reconciliation.reconciliation_resolution
SET resolution_code = 'OTHER';

ALTER TABLE reconciliation.reconciliation_resolution
    ALTER COLUMN resolution_code SET NOT NULL,
    ADD CONSTRAINT ck_reconciliation_resolution_code CHECK (
        resolution_code IN ('INTERNAL_CONFIRMED', 'CHANNEL_CONFIRMED', 'IGNORED_TEST_DATA', 'OTHER'));

UPDATE reconciliation.reconciliation_result result
SET assigned_to = resolution.operator,
    claimed_at = resolution.created_at
FROM reconciliation.reconciliation_resolution resolution
WHERE resolution.result_id = result.id
  AND result.resolution_status = 'RESOLVED';

ALTER TABLE reconciliation.reconciliation_result
    DROP CONSTRAINT ck_reconciliation_result_resolution,
    ADD CONSTRAINT ck_reconciliation_result_resolution CHECK (
        (result_type = 'MATCHED' AND resolution_status = 'NOT_REQUIRED'
            AND assigned_to IS NULL AND claimed_at IS NULL)
        OR (result_type <> 'MATCHED' AND resolution_status = 'OPEN'
            AND assigned_to IS NULL AND claimed_at IS NULL)
        OR (result_type <> 'MATCHED' AND resolution_status IN ('CLAIMED', 'RESOLVED')
            AND assigned_to IS NOT NULL AND length(trim(assigned_to)) > 0 AND claimed_at IS NOT NULL));

CREATE TABLE reconciliation.reconciliation_case_event (
    id UUID PRIMARY KEY,
    result_id UUID NOT NULL REFERENCES reconciliation.reconciliation_result(id),
    action VARCHAR(16) NOT NULL CHECK (action IN ('CLAIMED', 'RELEASED', 'RESOLVED')),
    actor VARCHAR(128) NOT NULL CHECK (length(trim(actor)) > 0),
    resolution_code VARCHAR(32),
    note VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reconciliation_case_event_details CHECK (
        (action IN ('CLAIMED', 'RELEASED') AND resolution_code IS NULL AND note IS NULL)
        OR (action = 'RESOLVED'
            AND resolution_code IN ('INTERNAL_CONFIRMED', 'CHANNEL_CONFIRMED', 'IGNORED_TEST_DATA', 'OTHER')
            AND note IS NOT NULL AND length(trim(note)) > 0))
);

CREATE INDEX idx_reconciliation_case_event_result
    ON reconciliation.reconciliation_case_event(result_id, created_at, id);

INSERT INTO reconciliation.reconciliation_case_event
    (id, result_id, action, actor, resolution_code, note, created_at)
SELECT gen_random_uuid(), resolution.result_id, 'RESOLVED', resolution.operator,
       resolution.resolution_code, resolution.note, resolution.created_at
FROM reconciliation.reconciliation_resolution resolution;

CREATE FUNCTION reconciliation.reject_case_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation case events are immutable';
END;
$$;

CREATE TRIGGER trg_reconciliation_case_event_immutable
BEFORE UPDATE OR DELETE ON reconciliation.reconciliation_case_event
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_case_event_mutation();
