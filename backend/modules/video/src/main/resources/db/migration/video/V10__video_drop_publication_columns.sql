-- publicationState and publicationIntent belong to the Publication module, not
-- the video aggregate (brief section 8's ownership table). Milestone 2 stored
-- them here as a shortcut before the Publication module existed; correcting
-- that now that publication.publication (Milestone 3) is the real owner.
ALTER TABLE video.video
    DROP COLUMN publication_state,
    DROP COLUMN publication_intent;
