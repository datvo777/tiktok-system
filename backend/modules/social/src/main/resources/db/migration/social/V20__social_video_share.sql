CREATE TABLE social.video_share (
    share_id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX video_share_video_idx ON social.video_share (video_id);
