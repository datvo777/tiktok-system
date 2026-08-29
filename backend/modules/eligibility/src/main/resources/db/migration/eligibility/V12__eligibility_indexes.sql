-- Milestone 5: account projection optimization + reconciliation/feed sweep
-- support. allTrackedAccountIds/allTrackedVideoIds and the feed's
-- findEligibleVideos query were doing a full-table sort on updated_at with no
-- supporting index.
CREATE INDEX account_eligibility_updated_at_idx ON eligibility.account_eligibility (updated_at);
CREATE INDEX video_eligibility_updated_at_idx ON eligibility.video_eligibility (updated_at);

-- Covers the feed's "is_video_eligible = true ORDER BY updated_at DESC" query directly.
CREATE INDEX video_eligibility_eligible_updated_idx
    ON eligibility.video_eligibility (updated_at DESC)
    WHERE is_video_eligible;
