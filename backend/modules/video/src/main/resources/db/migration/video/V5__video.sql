-- Video module owns this schema (brief section 9, section 7.1).
CREATE SCHEMA IF NOT EXISTS video;

CREATE TABLE video.video (
    video_id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL,
    -- Persisted as strings, never ordinals (brief section 7).
    processing_state VARCHAR(30) NOT NULL,
    -- Null until the Video module dispatches the first transcode job (brief section 7.1).
    processing_version INTEGER,
    durability_state VARCHAR(30) NOT NULL,
    publication_state VARCHAR(30) NOT NULL,
    publication_intent BOOLEAN NOT NULL DEFAULT false,
    asset_lifecycle_state VARCHAR(30) NOT NULL,
    legal_serving_state VARCHAR(30) NOT NULL,
    failure_class VARCHAR(20),
    source_object_key VARCHAR(500),
    master_playlist_key VARCHAR(500),
    variant_playlists TEXT,
    segment_count INTEGER,
    duration_seconds DOUBLE PRECISION,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX video_owner_idx ON video.video (owner_account_id);
CREATE INDEX video_processing_state_idx ON video.video (processing_state);
