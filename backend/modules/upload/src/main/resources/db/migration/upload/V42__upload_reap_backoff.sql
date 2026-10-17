-- Per-session retry state for the cleanup job. A session whose object cannot be removed stays
-- PENDING-and-expired; without this it sat at the head of the oldest-first sweep forever and a
-- full batch of them starved every newer session. Each failure pushes next_reap_at out, so the
-- sweep skips the session until then. Only the cleanup job writes these columns.
ALTER TABLE upload.upload_session ADD COLUMN reap_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE upload.upload_session ADD COLUMN next_reap_at TIMESTAMPTZ;
