-- A purge row can now name any prefix, not only processed/{video}/{version}/: removing a video
-- also has to reclaim its verified source under sources/{video}/. NULL keeps the old meaning
-- (derive processed/{video}/{version}/), so existing rows need no backfill.
ALTER TABLE video.superseded_asset ADD COLUMN purge_prefix VARCHAR(600);
