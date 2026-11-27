-- A record whose video was removed has nothing left to decide. closed_at takes it out of the review
-- queue and the held-record sweep without inventing a moderation state: the decision, if there ever
-- was one, is left as it was, and playback is already denied for a removed video.
ALTER TABLE moderation.moderation_record ADD COLUMN closed_at TIMESTAMPTZ;

DROP INDEX moderation.moderation_record_queue_idx;
CREATE INDEX moderation_record_queue_idx
    ON moderation.moderation_record (queued_at, video_id)
    WHERE state = 'PENDING' AND queued_at IS NOT NULL AND closed_at IS NULL;

DROP INDEX moderation.moderation_record_held_idx;
CREATE INDEX moderation_record_held_idx
    ON moderation.moderation_record (video_id)
    WHERE state = 'PENDING' AND queued_at IS NULL AND closed_at IS NULL;
