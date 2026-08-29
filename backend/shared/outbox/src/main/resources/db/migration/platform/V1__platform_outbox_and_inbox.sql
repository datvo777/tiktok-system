-- Shared infrastructure tables (brief sections 9 and 10). Owned by backend/shared.
CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    claimed_by VARCHAR(150),
    claim_token UUID,
    claimed_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    -- One canonical absolute-state event per aggregate transition.
    UNIQUE (aggregate_type, aggregate_id, aggregate_version)
);

CREATE INDEX outbox_event_claimable_idx
    ON platform.outbox_event (available_at, occurred_at, aggregate_id, aggregate_version)
    WHERE status IN ('PENDING', 'RETRY', 'CLAIMED');

CREATE INDEX outbox_event_dead_idx
    ON platform.outbox_event (status, last_attempt_at)
    WHERE status = 'DEAD';

-- Durable consumer inbox. Insert first; on duplicate key, acknowledge without
-- reapplying the business update (Rule 5).
CREATE TABLE platform.consumed_event (
    consumer_name VARCHAR(150) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX consumed_event_processed_at_idx
    ON platform.consumed_event (processed_at);
