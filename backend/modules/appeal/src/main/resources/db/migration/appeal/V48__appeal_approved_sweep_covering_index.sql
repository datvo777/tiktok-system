-- The reconcile sweep pages approved appeals in (updated_at, video_id) order and reads only the
-- video id, the moderation version and the decision time, so this index answers it without
-- visiting the table. It replaces the one from V47, which had the same keys but no moderation_version.
DROP INDEX appeal.appeal_approved_updated_idx;
CREATE INDEX appeal_approved_sweep_idx ON appeal.appeal (updated_at, video_id) INCLUDE (moderation_version) WHERE state = 'APPROVED';
