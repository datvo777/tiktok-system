-- Supports the keyset-paginated admin queue (ModerationJpaRepository.findPageAfter):
-- WHERE state = ? AND (created_at, video_id) > (?, ?) ORDER BY created_at, video_id.
-- Without this, the query has no index to seek from and falls back to a full
-- scan + sort of every PENDING row on each page (state alone isn't enough,
-- since the ordering/seek columns aren't part of moderation_record_state_idx).
CREATE INDEX moderation_record_state_created_video_idx
    ON moderation.moderation_record (state, created_at, video_id);
