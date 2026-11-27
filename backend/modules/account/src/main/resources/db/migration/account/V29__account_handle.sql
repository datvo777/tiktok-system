-- Real usernames.
--
-- Accounts had a display name and a UUID and nothing else, so the client
-- fabricated a handle from the id: `@` plus the first ten hex characters. That
-- string is not mentionable, not typeable, not memorable, and not stable in any
-- way a person can rely on -- and every comment in the app rendered as one.
--
-- Nullable at first so this migration cannot fail on an existing row; the
-- backfill below fills every one, and the application refuses to create an
-- account without a handle.
ALTER TABLE account.account
    ADD COLUMN handle VARCHAR(30),
    -- Kept alongside `handle` rather than derived on read, because uniqueness has
    -- to be enforced case-insensitively: @Dat and @dat must not be two accounts.
    -- A functional unique index on lower(handle) would do the same job, but a
    -- stored column is also what lookups match on, so it earns its place twice.
    ADD COLUMN handle_lower VARCHAR(30);

-- Backfill: seed from the id, exactly matching the string the client used to
-- fabricate, so nobody's apparent handle changes on the day this ships. They can
-- pick a real one afterwards.
UPDATE account.account
SET handle = substr(replace(account_id::text, '-', ''), 1, 10),
    handle_lower = substr(replace(account_id::text, '-', ''), 1, 10)
WHERE handle IS NULL;

ALTER TABLE account.account
    ALTER COLUMN handle SET NOT NULL,
    ALTER COLUMN handle_lower SET NOT NULL;

CREATE UNIQUE INDEX account_handle_key ON account.account (handle_lower);
