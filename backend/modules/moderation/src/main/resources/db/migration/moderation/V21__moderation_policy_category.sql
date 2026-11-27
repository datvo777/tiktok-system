-- Brief section 18: a rejection reason becomes structured.
--
-- `reason` is kept, but demoted to optional free-text elaboration. It stays
-- because a reviewer sometimes has something to say that no taxonomy covers;
-- it is no longer what anything counts or routes on.
ALTER TABLE moderation.moderation_record
    ADD COLUMN policy_category VARCHAR(40),
    ADD COLUMN policy_tier VARCHAR(4);

-- Rows written since the console's policy picker shipped already carry a
-- canonical slug as the first token of `reason` (optionally followed by
-- " — free text"), so they map exactly. That was the point of writing slugs
-- into an unstructured column rather than prose.
UPDATE moderation.moderation_record
   SET policy_category = upper(split_part(reason, ' ', 1))
 WHERE state = 'REJECTED'
   AND reason IS NOT NULL
   AND upper(split_part(reason, ' ', 1)) IN (
       'MINOR_SAFETY', 'SELF_HARM', 'VIOLENCE_GORE', 'ADULT_CONTENT',
       'HARASSMENT', 'SPAM_DECEPTIVE', 'IP_VIOLATION');

-- Everything older predates the taxonomy entirely. Marking it UNCLASSIFIED is
-- honest; back-filling a guess would poison exactly the metrics this unlocks.
UPDATE moderation.moderation_record
   SET policy_category = 'UNCLASSIFIED'
 WHERE state = 'REJECTED'
   AND policy_category IS NULL;

UPDATE moderation.moderation_record
   SET policy_tier = CASE policy_category
       WHEN 'MINOR_SAFETY'   THEN 'P0'
       WHEN 'SELF_HARM'      THEN 'P0'
       WHEN 'VIOLENCE_GORE'  THEN 'P1'
       WHEN 'ADULT_CONTENT'  THEN 'P1'
       WHEN 'HARASSMENT'     THEN 'P1'
       WHEN 'SPAM_DECEPTIVE' THEN 'P2'
       WHEN 'IP_VIOLATION'   THEN 'P2'
       ELSE 'P3'
   END
 WHERE policy_category IS NOT NULL;

-- Supports "rejection mix by policy" and, later, severity-ordered queueing.
CREATE INDEX moderation_record_policy_idx
    ON moderation.moderation_record (policy_category, policy_tier);
