-- Milestone 3: a second event source (moderation) starts writing into
-- video_eligibility, so a single combined source_version can no longer guard
-- staleness correctly (brief section 17 tracks one version per source).
ALTER TABLE eligibility.video_eligibility RENAME COLUMN source_version TO processing_version_source;
ALTER TABLE eligibility.video_eligibility ADD COLUMN moderation_state VARCHAR(30) NOT NULL DEFAULT 'PENDING';
ALTER TABLE eligibility.video_eligibility ADD COLUMN moderation_version_source BIGINT NOT NULL DEFAULT 0;
ALTER TABLE eligibility.video_eligibility ADD COLUMN publication_version_source BIGINT NOT NULL DEFAULT 0;
