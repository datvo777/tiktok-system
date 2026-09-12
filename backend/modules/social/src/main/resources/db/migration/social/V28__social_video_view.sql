-- Watch tracking.
--
-- Likes, comments and shares were all recorded; plays were not. Two things
-- followed. Creators had no evidence anyone was watching, and the feed could
-- not rank on the one signal that actually predicts what people want -- so
-- FeedScorer weighted likes and recency and nothing else, and nothing at all
-- stopped it serving the same videos over and over, because it had no idea
-- which ones the viewer had already seen.
--
-- One row per (video, viewer) rather than one per play: a rewatch is a counter
-- bump, not a new row. That keeps the table proportional to distinct viewers
-- instead of to total plays, and it makes "has this viewer seen this video?"
-- -- the query the feed needs on every ranking -- a primary key lookup.
CREATE TABLE social.video_view (
    video_id UUID NOT NULL,
    account_id UUID NOT NULL,
    -- Rewatches by the same person. The public view count is the number of
    -- distinct viewers, not the sum of this.
    play_count INT NOT NULL DEFAULT 1,
    total_watched_ms BIGINT NOT NULL DEFAULT 0,
    -- Whether this viewer has ever watched it through. Completion rate is the
    -- strongest quality signal a short-video feed has.
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    first_viewed_at TIMESTAMPTZ NOT NULL,
    last_viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (video_id, account_id)
);

-- "What has this viewer already seen?", asked once per feed ranking over the
-- whole candidate set.
CREATE INDEX video_view_account_idx ON social.video_view (account_id, last_viewed_at DESC);
