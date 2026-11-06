-- Parallel to mentioned_account_ids (same order, same length): the literal
-- @handle text matched in the body at write time, for a link that still finds
-- the right span in the (immutable) body after the account renames.
ALTER TABLE social.comment
    ADD COLUMN mentioned_handles TEXT[] NOT NULL DEFAULT '{}';
