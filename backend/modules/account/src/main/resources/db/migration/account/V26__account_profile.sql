-- Profile fields a person can actually change about themselves.
--
-- Display name was previously fixed at registration and immutable: the account
-- module exposed no update of any kind, so a typo in a name was permanent. The
-- column already existed; what was missing was any way to write to it.
--
-- `bio` is new. Nullable, because an account created before this had none and a
-- forced empty string is not a better default than "not set".
ALTER TABLE account.account
    ADD COLUMN bio VARCHAR(300);
