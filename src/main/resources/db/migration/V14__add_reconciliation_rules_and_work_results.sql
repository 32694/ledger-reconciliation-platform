CREATE TABLE reconciliation.reconciliation_channel (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE FUNCTION reconciliation.reject_reconciliation_channel_code_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.code IS DISTINCT FROM NEW.code THEN
        RAISE EXCEPTION 'reconciliation channel code is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reconciliation_channel_code_immutable
BEFORE UPDATE ON reconciliation.reconciliation_channel
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_reconciliation_channel_code_change();

CREATE TABLE reconciliation.reconciliation_rule (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('DEFAULT', 'CHANNEL')),
    channel_id UUID REFERENCES reconciliation.reconciliation_channel(id),
    active_version_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_reconciliation_rule_scope CHECK (
        (scope_type = 'DEFAULT' AND channel_id IS NULL)
        OR (scope_type = 'CHANNEL' AND channel_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_reconciliation_rule_default
    ON reconciliation.reconciliation_rule(scope_type)
    WHERE scope_type = 'DEFAULT';

CREATE UNIQUE INDEX uq_reconciliation_rule_channel
    ON reconciliation.reconciliation_rule(channel_id)
    WHERE scope_type = 'CHANNEL';

CREATE TABLE reconciliation.reconciliation_rule_version (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES reconciliation.reconciliation_rule(id),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    amount_tolerance_cents BIGINT NOT NULL CHECK (amount_tolerance_cents >= 0),
    query_window_hours INTEGER NOT NULL CHECK (query_window_hours BETWEEN 0 AND 168),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_by VARCHAR(128),
    published_at TIMESTAMPTZ,
    UNIQUE (rule_id, version_number),
    CONSTRAINT ck_reconciliation_rule_version_publish_audit CHECK (
        (status = 'DRAFT' AND published_by IS NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_by IS NOT NULL
            AND length(trim(published_by)) > 0 AND published_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_reconciliation_rule_version_draft
    ON reconciliation.reconciliation_rule_version(rule_id)
    WHERE status = 'DRAFT';

ALTER TABLE reconciliation.reconciliation_rule
    ADD CONSTRAINT fk_reconciliation_rule_active_version
    FOREIGN KEY (active_version_id) REFERENCES reconciliation.reconciliation_rule_version(id);

CREATE FUNCTION reconciliation.protect_published_reconciliation_rule_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'published reconciliation rule versions are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'published reconciliation rule versions are immutable';
    END IF;
    IF TG_OP = 'UPDATE'
            AND OLD.status <> NEW.status
            AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'reconciliation rule version status can only change from DRAFT to PUBLISHED';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reconciliation_rule_version_immutable
BEFORE UPDATE OR DELETE ON reconciliation.reconciliation_rule_version
FOR EACH ROW EXECUTE FUNCTION reconciliation.protect_published_reconciliation_rule_version();

INSERT INTO reconciliation.reconciliation_channel
    (id, code, display_name, active, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'ALIPAY', 'Alipay', true, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000002', 'WECHAT_PAY', 'WeChat Pay', true, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000003', 'UNION_PAY', 'UnionPay', true, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000004', 'LEGACY_SYNTHETIC', 'Legacy synthetic', false, CURRENT_TIMESTAMP);

INSERT INTO reconciliation.reconciliation_rule (id, scope_type)
VALUES ('00000000-0000-0000-0000-000000000101', 'DEFAULT');

INSERT INTO reconciliation.reconciliation_rule (id, scope_type, channel_id)
VALUES
    ('00000000-0000-0000-0000-000000000102', 'CHANNEL', '00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-000000000103', 'CHANNEL', '00000000-0000-0000-0000-000000000002'),
    ('00000000-0000-0000-0000-000000000104', 'CHANNEL', '00000000-0000-0000-0000-000000000003');

INSERT INTO reconciliation.reconciliation_rule_version
    (id, rule_id, version_number, status, amount_tolerance_cents, query_window_hours,
     created_by, created_at, published_by, published_at)
VALUES
    ('00000000-0000-0000-0000-000000000201',
     '00000000-0000-0000-0000-000000000101', 1, 'PUBLISHED', 0, 0,
     'system', CURRENT_TIMESTAMP, 'system', CURRENT_TIMESTAMP);

UPDATE reconciliation.reconciliation_rule
SET active_version_id = '00000000-0000-0000-0000-000000000201'
WHERE id = '00000000-0000-0000-0000-000000000101';

ALTER TABLE reconciliation.reconciliation_batch
    ADD COLUMN channel_id UUID,
    ADD COLUMN rule_version_id UUID;

UPDATE reconciliation.reconciliation_batch
SET channel_id = '00000000-0000-0000-0000-000000000004',
    rule_version_id = '00000000-0000-0000-0000-000000000201';

ALTER TABLE reconciliation.reconciliation_batch
    ALTER COLUMN channel_id SET DEFAULT '00000000-0000-0000-0000-000000000004',
    ALTER COLUMN channel_id SET NOT NULL,
    ALTER COLUMN rule_version_id SET DEFAULT '00000000-0000-0000-0000-000000000201',
    ALTER COLUMN rule_version_id SET NOT NULL,
    ADD CONSTRAINT fk_reconciliation_batch_channel
        FOREIGN KEY (channel_id) REFERENCES reconciliation.reconciliation_channel(id),
    ADD CONSTRAINT fk_reconciliation_batch_rule_version
        FOREIGN KEY (rule_version_id) REFERENCES reconciliation.reconciliation_rule_version(id);

ALTER TABLE reconciliation.reconciliation_run
    ADD COLUMN batch_job_instance_id BIGINT,
    ADD COLUMN batch_job_execution_id BIGINT,
    ADD COLUMN current_step VARCHAR(64),
    ADD COLUMN processed_items INTEGER NOT NULL DEFAULT 0 CHECK (processed_items >= 0),
    ADD COLUMN total_items INTEGER NOT NULL DEFAULT 0 CHECK (total_items >= 0),
    ADD COLUMN restart_count INTEGER NOT NULL DEFAULT 0 CHECK (restart_count >= 0);

CREATE TABLE reconciliation.reconciliation_result_work (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation.reconciliation_run(id),
    batch_id UUID NOT NULL REFERENCES reconciliation.reconciliation_batch(id),
    statement_entry_id UUID REFERENCES reconciliation.channel_statement_entry(id),
    payment_id UUID,
    result_type VARCHAR(32) NOT NULL CHECK (result_type IN
        ('MATCHED', 'AMOUNT_MISMATCH', 'CHANNEL_ONLY', 'INTERNAL_ONLY')),
    resolution_status VARCHAR(16) NOT NULL CHECK (resolution_status IN ('NOT_REQUIRED', 'OPEN')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reconciliation_result_work_refs CHECK (
        (result_type = 'INTERNAL_ONLY' AND statement_entry_id IS NULL AND payment_id IS NOT NULL)
        OR (result_type = 'CHANNEL_ONLY' AND statement_entry_id IS NOT NULL AND payment_id IS NULL)
        OR (result_type IN ('MATCHED', 'AMOUNT_MISMATCH')
            AND statement_entry_id IS NOT NULL AND payment_id IS NOT NULL)),
    CONSTRAINT ck_reconciliation_result_work_resolution CHECK (
        (result_type = 'MATCHED' AND resolution_status = 'NOT_REQUIRED')
        OR (result_type <> 'MATCHED' AND resolution_status = 'OPEN'))
);

CREATE UNIQUE INDEX uq_reconciliation_result_work_statement
    ON reconciliation.reconciliation_result_work(run_id, statement_entry_id)
    WHERE statement_entry_id IS NOT NULL;

CREATE UNIQUE INDEX uq_reconciliation_result_work_payment
    ON reconciliation.reconciliation_result_work(run_id, payment_id)
    WHERE payment_id IS NOT NULL;
