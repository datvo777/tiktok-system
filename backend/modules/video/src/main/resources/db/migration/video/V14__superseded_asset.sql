-- Tracks a superseded processingVersion's object prefix pending physical
-- deletion from MinIO (brief section 7.1, Milestone 6).
CREATE TABLE video.superseded_asset (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    processing_version INTEGER NOT NULL,
    master_playlist_key VARCHAR(500),
    variant_playlists TEXT,
    state VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX superseded_asset_state_idx ON video.superseded_asset (state);
CREATE INDEX superseded_asset_video_idx ON video.superseded_asset (video_id);
