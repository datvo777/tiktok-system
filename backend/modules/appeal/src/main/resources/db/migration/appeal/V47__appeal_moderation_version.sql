-- The moderation aggregate version of the rejection this appeal was filed against. A video can be
-- reinstated and rejected again while an appeal is open, or an approval can be redelivered after a
-- later rejection; without this an approval cannot tell which rejection it was meant to reverse and
-- would reinstate the new one. Rows that predate the column get 0, which no rejection of a decided
-- appeal will match, so an old approval cannot reinstate anything by accident.
ALTER TABLE appeal.appeal ADD COLUMN moderation_version BIGINT NOT NULL DEFAULT 0;

-- The reconcile sweep that finds approved appeals whose approval never reached moderation.
CREATE INDEX appeal_approved_updated_idx ON appeal.appeal (updated_at, video_id) WHERE state = 'APPROVED';
