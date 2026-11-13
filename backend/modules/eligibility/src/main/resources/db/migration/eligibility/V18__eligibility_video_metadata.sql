-- Fourth independent source alongside processing/moderation/publication (brief
-- section 17): title/description projected from video.metadata.set, guarded by
-- its own version column so it can never be clobbered by a stale write from one
-- of the other three sources.
ALTER TABLE eligibility.video_eligibility
    ADD COLUMN title VARCHAR(150),
    ADD COLUMN description VARCHAR(2000),
    ADD COLUMN metadata_version_source BIGINT NOT NULL DEFAULT 0;
