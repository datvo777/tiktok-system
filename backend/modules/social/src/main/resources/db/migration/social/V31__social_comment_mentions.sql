-- @handle mentions are resolved to account ids at write time (not re-parsed
-- from body on every read), so a mention keeps pointing at the same person
-- even after they rename or lose that handle.
ALTER TABLE social.comment
    ADD COLUMN mentioned_account_ids UUID[] NOT NULL DEFAULT '{}';
