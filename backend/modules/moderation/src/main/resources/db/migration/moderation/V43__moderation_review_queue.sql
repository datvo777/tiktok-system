-- The human review queue stops being "every PENDING row". A record is queued once a person
-- actually needs to look at it: queued_at is NULL while it is only being held (screened, not
-- risky, waiting for its owner to ask to publish).
ALTER TABLE moderation.moderation_record
    ADD COLUMN queued_at TIMESTAMPTZ,
    ADD COLUMN queue_reason VARCHAR(20);

-- Everything waiting today is already in the queue; without this the queue would empty on deploy.
UPDATE moderation.moderation_record
   SET queued_at = created_at, queue_reason = 'UPLOAD'
 WHERE state = 'PENDING';

-- Supports the keyset-paginated admin queue (ModerationJpaRepository.findPageAfter):
-- WHERE state = 'PENDING' AND queued_at IS NOT NULL AND (queued_at, video_id) > (?, ?).
CREATE INDEX moderation_record_queue_idx
    ON moderation.moderation_record (queued_at, video_id)
    WHERE state = 'PENDING' AND queued_at IS NOT NULL;

-- Supports the held-record sweep (HeldReviewReconciliationJob): PENDING rows nobody has queued.
CREATE INDEX moderation_record_held_idx
    ON moderation.moderation_record (video_id)
    WHERE state = 'PENDING' AND queued_at IS NULL;
