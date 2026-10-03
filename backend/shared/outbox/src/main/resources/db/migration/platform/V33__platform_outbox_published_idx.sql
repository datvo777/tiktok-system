-- Backs OutboxCleanupJob's sweep (WHERE status = 'PUBLISHED' AND published_at < ?).
-- Mirrors outbox_event_claimable_idx and outbox_event_dead_idx: a partial index
-- per status, so the row count in any one state never affects the others.
CREATE INDEX outbox_event_published_idx
    ON platform.outbox_event (published_at)
    WHERE status = 'PUBLISHED';
