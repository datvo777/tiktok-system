-- Create-upload idempotency (brief section 12.2): a client that times out and retries
-- POST /uploads with the same Idempotency-Key gets the original session back instead of
-- a second draft, a second session and a second slice of quota.
-- Separate from idempotency_key, which records the key sent to /complete.
ALTER TABLE upload.upload_session ADD COLUMN create_idempotency_key VARCHAR(200);

CREATE UNIQUE INDEX upload_session_create_idem_key
    ON upload.upload_session (account_id, create_idempotency_key)
    WHERE create_idempotency_key IS NOT NULL;
