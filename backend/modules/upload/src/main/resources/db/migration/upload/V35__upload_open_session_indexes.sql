-- Composite rather than partial (WHERE status = 'PENDING') on purpose: the queries bind
-- status as a parameter, and a generic prepared plan cannot prove a parameter equals the
-- partial index's predicate, so it silently falls back to account_idx / a full scan.

-- Per-account open-session quota count (account_id, status, expires_at > now).
CREATE INDEX upload_session_account_status_expiry_idx
    ON upload.upload_session (account_id, status, expires_at);

-- Cleanup sweep (status = PENDING, expires_at < now, ordered by expires_at).
CREATE INDEX upload_session_status_expiry_idx
    ON upload.upload_session (status, expires_at);
