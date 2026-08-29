-- Upload module owns this schema (brief section 9, section 7.1, section 12.2).
CREATE SCHEMA IF NOT EXISTS upload;

CREATE TABLE upload.upload_session (
    upload_id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    account_id UUID NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    -- Persisted as a string, never an ordinal (brief section 7).
    status VARCHAR(30) NOT NULL,
    min_size_bytes BIGINT NOT NULL,
    max_size_bytes BIGINT NOT NULL,
    completed_size_bytes BIGINT,
    idempotency_key VARCHAR(200),
    expires_at TIMESTAMPTZ NOT NULL,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX upload_session_video_id_key ON upload.upload_session (video_id);
CREATE INDEX upload_session_account_idx ON upload.upload_session (account_id);
