-- Reconciliation scan of completed sessions in a recent time window (status = COMPLETED,
-- updated_at between ...). Composite for the same reason as the indexes above.
CREATE INDEX upload_session_status_updated_idx
    ON upload.upload_session (status, updated_at);
