-- Which try of the current processingVersion's transcode job is in flight. A TRANSIENT failure
-- re-dispatches the same jobId and counts up until the retry budget is spent (brief section 11.1).
-- Reset to 1 whenever a new processingVersion is dispatched.
ALTER TABLE video.video ADD COLUMN transcode_attempt INTEGER NOT NULL DEFAULT 1;
