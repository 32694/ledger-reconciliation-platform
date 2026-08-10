CREATE SCHEMA messaging;
CREATE SCHEMA notification;

CREATE TABLE messaging.outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    event_type varchar(64) NOT NULL,
    schema_version int NOT NULL CHECK (schema_version > 0),
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    locked_at timestamptz,
    published_at timestamptz,
    last_error varchar(2000),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX ix_outbox_event_claim
    ON messaging.outbox_event (status, next_attempt_at, created_at);

CREATE TABLE notification.consumed_message (
    event_id uuid PRIMARY KEY,
    queue_name varchar(128) NOT NULL,
    event_type varchar(64) NOT NULL,
    consumed_at timestamptz NOT NULL
);

CREATE TABLE notification.notification (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    notification_type varchar(64) NOT NULL,
    title varchar(200) NOT NULL,
    content varchar(1000) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz
);

CREATE INDEX ix_notification_created
    ON notification.notification (created_at DESC, id DESC);
