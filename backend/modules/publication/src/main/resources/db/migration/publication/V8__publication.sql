-- Publication module owns this schema (brief section 9, section 8 ownership table).
-- publicationState and publicationIntent belong here, not on the video aggregate.
CREATE SCHEMA IF NOT EXISTS publication;

CREATE TABLE publication.publication (
    video_id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL,
    -- Persisted as a string, never an ordinal (brief section 7).
    state VARCHAR(30) NOT NULL,
    intent BOOLEAN NOT NULL DEFAULT false,
    processing_ready BOOLEAN NOT NULL DEFAULT false,
    moderation_approved BOOLEAN NOT NULL DEFAULT false,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX publication_state_idx ON publication.publication (state);
CREATE INDEX publication_owner_idx ON publication.publication (owner_account_id);
