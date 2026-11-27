-- Tracks when the password was last set, so a stolen or leaked token issued
-- before a password change can be rejected without waiting out its TTL.
--
-- Backfilled from created_at rather than now(): a DEFAULT now() here would mark
-- every existing session token as "issued before the password changed" and log
-- every signed-in account out the moment this migration runs.
ALTER TABLE account.account
    ADD COLUMN password_changed_at TIMESTAMPTZ;

UPDATE account.account SET password_changed_at = created_at WHERE password_changed_at IS NULL;

ALTER TABLE account.account
    ALTER COLUMN password_changed_at SET NOT NULL;
