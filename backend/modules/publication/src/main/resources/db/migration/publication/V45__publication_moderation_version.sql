-- The moderation aggregate version this row last applied. Approved and rejected do not commute, so
-- a decision that arrives late (a DLT replay, a redelivery after the inbox was pruned) must be
-- recognisable as older than the one already applied. -1: nothing applied yet; moderation starts at 0.
ALTER TABLE publication.publication ADD COLUMN moderation_version BIGINT NOT NULL DEFAULT -1;
