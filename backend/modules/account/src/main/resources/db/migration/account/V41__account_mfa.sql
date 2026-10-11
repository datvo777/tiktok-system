-- Second factor (docs/mfa-plan.md). Account module owns this schema.

-- The last time the second factor changed. A token issued before it stops working, the same as one issued
-- before a password change; JdbcCredentialFreshnessReader takes the later of the two.
ALTER TABLE account.account ADD COLUMN mfa_changed_at TIMESTAMPTZ;

CREATE TABLE account.mfa_factor (
    factor_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account.account (account_id),
    -- Persisted as strings, never ordinals (brief section 7).
    type VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL,
    -- AES-GCM: 12-byte nonce followed by the ciphertext and tag. key_id says which key sealed it.
    secret_enc BYTEA NOT NULL,
    key_id VARCHAR(20) NOT NULL,
    -- The last 30-second step whose code was accepted. A code for a step at or before it is refused, so a
    -- code that was seen cannot be used again.
    last_used_step BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ
);

-- One active factor of each type per account; any number of pending ones is prevented by the service.
CREATE UNIQUE INDEX mfa_factor_active_key ON account.mfa_factor (account_id, type) WHERE state = 'ACTIVE';
CREATE INDEX mfa_factor_account_idx ON account.mfa_factor (account_id);

CREATE TABLE account.mfa_recovery_code (
    account_id UUID NOT NULL REFERENCES account.account (account_id),
    -- SHA-256 of the normalised code. The codes are random and long enough that a plain hash is sufficient.
    code_hash BYTEA NOT NULL,
    used_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, code_hash)
);
