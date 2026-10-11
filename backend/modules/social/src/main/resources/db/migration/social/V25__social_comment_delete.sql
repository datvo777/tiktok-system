-- Soft delete for comments.
--
-- A comment was previously permanent: not even its author could remove one, and
-- a creator had no way to moderate the comment section on their own video.
--
-- Soft rather than hard, for two reasons. Replies reference their parent, so a
-- hard delete of a parent either orphans them or needs a cascade that silently
-- destroys other people's writing. And a deleted comment is exactly the kind of
-- thing an abuse investigation later needs to see, which is the same argument
-- that put REJECTED_RETAINED in the video lifecycle.
ALTER TABLE social.comment
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID;

-- Every listing and count filters on deleted_at IS NULL, so the index that backs
-- them has to carry it too or each read falls back to a scan of the video's
-- whole comment history including the deleted rows.
CREATE INDEX comment_video_live_idx
    ON social.comment (video_id, created_at)
    WHERE deleted_at IS NULL;
