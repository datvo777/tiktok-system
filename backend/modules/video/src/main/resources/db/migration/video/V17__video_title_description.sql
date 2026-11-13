-- Captured at upload time (brief section 7.1); nullable so existing rows created
-- before this feature don't need a backfill.
ALTER TABLE video.video
    ADD COLUMN title VARCHAR(150),
    ADD COLUMN description VARCHAR(2000);
