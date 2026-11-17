-- Added in V16 for the queue's keyset seek on (state, created_at, video_id). The queue now pages
-- on (queued_at, video_id) through the partial moderation_record_queue_idx, and no query orders or
-- filters on created_at any more, so this index only costs a write on every insert and state change.
DROP INDEX IF EXISTS moderation.moderation_record_state_created_video_idx;
