-- When a video first became eligible to be served, kept for the feed's freshness and candidate pool.
--
-- The feed used updated_at for both, but updated_at moves on every write to the row: unpublishing
-- and publishing again, a moderation decision, an asset lifecycle change. Each of those made an old
-- video look new again, so a creator who toggled publication (the toggle limiter only slows that
-- down) could keep a video at the top of the pool, and a reinstatement after an appeal did the same
-- without anyone intending it. This column is set once, the first time the video becomes eligible,
-- and never moved afterwards.
--
-- Null for a video that has never been eligible. The recompute that decides is_video_eligible fills
-- it in the same statement that flips it.
ALTER TABLE eligibility.video_eligibility ADD COLUMN first_eligible_at TIMESTAMPTZ;

-- The best value available for a video that is already live: the last time its row was written.
-- That is no worse than what the feed used until now, and later writes can no longer move it.
-- On a large table this rewrites every eligible row in the migration transaction; run it in a
-- quiet period, as V51 asks for its backfill.
UPDATE eligibility.video_eligibility SET first_eligible_at = updated_at WHERE is_video_eligible;

-- Serves "the newest eligible videos" the way video_eligibility_eligible_updated_idx did for
-- updated_at. NULLS LAST is spelled out so the index and the queries agree on where a missing
-- value would go; an eligible row is never null once this migration has run.
CREATE INDEX video_eligibility_eligible_first_idx
    ON eligibility.video_eligibility (first_eligible_at DESC NULLS LAST)
    WHERE is_video_eligible;
