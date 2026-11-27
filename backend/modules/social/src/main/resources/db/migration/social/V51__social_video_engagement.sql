-- Running like and live-comment totals per video, for the feed to rank on.
--
-- A ranking scores 200 candidate videos and used to count their likes and comments from
-- the source tables on every computation. That is linear in the engagement rows of those
-- videos: about 43 ms to count 600,000 likes on PostgreSQL 17, and it grows with every
-- like. This table makes it a primary-key lookup of 200 rows whatever the engagement is.
--
-- The counts are kept up to date from events, not in the transaction that writes a like.
-- A synchronous counter would make every like of a viral video update the same row, so
-- they would queue behind each other on its lock. Each like or comment emits one
-- social.video.engagement.changed delta through the outbox, and a consumer applies it
-- here. The counts therefore trail the source tables by a few seconds, which a ranking
-- tolerates (it is already cached for minutes); the source tables stay authoritative and
-- a reconciliation job corrects any drift.
--
-- A video with no engagement has no row; readers treat that as zero.
CREATE TABLE social.video_engagement (
    video_id UUID PRIMARY KEY,
    -- May dip below zero for an instant if an unlike's delta is applied before the like's,
    -- since deltas commute. Readers clamp at zero rather than storing a floor, because a
    -- floor would make the later like's delta land one too high.
    like_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    -- When a delta last touched the row. The reconciliation job leaves recently touched
    -- rows alone: a correction made while a delta is still in flight would be applied twice.
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill from the source tables. Events start flowing once this code is deployed, so
-- what is counted here is exactly what the events will not cover. An instance still on
-- the old code during a rolling deploy emits no delta; the reconciliation job repairs
-- whatever that leaves behind.
--
-- On a large production table this is one pass over social.video_like and social.comment
-- inside the migration transaction; run it in a quiet period.
INSERT INTO social.video_engagement (video_id, like_count, comment_count)
SELECT COALESCE(l.video_id, c.video_id), COALESCE(l.n, 0), COALESCE(c.n, 0)
FROM (SELECT video_id, count(*) AS n FROM social.video_like GROUP BY video_id) l
FULL OUTER JOIN (
    SELECT video_id, count(*) AS n FROM social.comment WHERE deleted_at IS NULL GROUP BY video_id
) c ON c.video_id = l.video_id;
