-- Durable record of signed-out (and rotated-out) session tokens. The logout deny-list used to live only
-- in Redis, which keeps nothing across a restart or a flush: every token revoked in the last
-- token-lifetime became valid again, and a refresh token could be redeemed twice. Redis still answers
-- the per-request question; this table is what it is rebuilt from and what answers when Redis cannot.
--
-- A row is only useful until the token it names would have expired anyway (expires_at already carries
-- the clock-skew margin), after which it is deleted.
CREATE TABLE platform.revoked_session (
    token_id VARCHAR(100) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX revoked_session_expires_idx ON platform.revoked_session (expires_at);
