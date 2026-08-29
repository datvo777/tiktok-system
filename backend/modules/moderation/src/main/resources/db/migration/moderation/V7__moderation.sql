-- Moderation module owns this schema (brief section 9, section 18).
CREATE SCHEMA IF NOT EXISTS moderation;

CREATE TABLE moderation.moderation_record (
    video_id UUID PRIMARY KEY,
    creator_id UUID NOT NULL,
    -- Persisted as a string, never an ordinal (brief section 7).
    state VARCHAR(30) NOT NULL,
    reason VARCHAR(200),
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX moderation_record_state_idx ON moderation.moderation_record (state);
CREATE INDEX moderation_record_creator_idx ON moderation.moderation_record (creator_id);
