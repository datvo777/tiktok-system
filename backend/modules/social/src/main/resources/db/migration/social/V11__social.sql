-- Social module owns this schema (brief section 9, section 12).
CREATE SCHEMA IF NOT EXISTS social;

CREATE TABLE social.video_like (
    video_id UUID NOT NULL,
    account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (video_id, account_id)
);

CREATE INDEX video_like_account_idx ON social.video_like (account_id);

CREATE TABLE social.comment (
    comment_id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    account_id UUID NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX comment_video_idx ON social.comment (video_id, created_at);

-- Backs the feed's followedCreatorBoost signal (brief section 15).
CREATE TABLE social.follow (
    follower_id UUID NOT NULL,
    followee_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (follower_id, followee_id)
);

CREATE INDEX follow_followee_idx ON social.follow (followee_id);
