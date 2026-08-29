-- Account module owns this schema (brief section 9).
CREATE SCHEMA IF NOT EXISTS account;

CREATE TABLE account.account (
    account_id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    -- Persisted as a string, never an ordinal (brief section 7).
    state VARCHAR(30) NOT NULL,
    roles VARCHAR(200) NOT NULL DEFAULT 'USER',
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX account_email_key ON account.account (email);
CREATE INDEX account_state_idx ON account.account (state);
