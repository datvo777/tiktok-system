-- Appeal module owns this schema (brief section 9, Milestone 6).
CREATE SCHEMA IF NOT EXISTS appeal;

CREATE TABLE appeal.appeal (
    video_id UUID PRIMARY KEY,
    creator_id UUID NOT NULL,
    -- Persisted as strings, never ordinals (brief section 7).
    state VARCHAR(30) NOT NULL,
    reason VARCHAR(1000),
    decision_reason VARCHAR(1000),
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX appeal_creator_idx ON appeal.appeal (creator_id);
CREATE INDEX appeal_state_idx ON appeal.appeal (state);
