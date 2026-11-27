-- Indexes in claim order, so claiming 100 rows reads about 100 index entries however large the
-- backlog is. OutboxRepository.CLAIM reads each branch in this order and stops at its LIMIT.
--
-- outbox_event_claimable_idx leads with available_at, which the claim does not sort by, so with
-- the old query every claim read and sorted the whole claimable set (measured on PostgreSQL 17.4:
-- ~49 ms at a 200,000-row backlog, growing with it; ~0.6 ms with these).
--
-- Two indexes because the claim has two unrelated sources, and an OR across them would stop
-- the planner reading either in order. The reclaim index is also what lets buryAbandoned find
-- expired leases without reading the claimable set (21 ms -> 0.07 ms at the same backlog).
--
-- outbox_event_claimable_idx is dropped here: neither statement can use it any more, and it would
-- only cost a write on every status change. Nothing else reads it.
--
-- Not CONCURRENTLY (index builds or the drop): Flyway runs this in a transaction, and the outbox
-- holds only unpublished rows plus the retention window, so both are short. Revisit if that
-- stops being true.
CREATE INDEX outbox_event_ready_idx
    ON platform.outbox_event (occurred_at, aggregate_id, aggregate_version)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX outbox_event_reclaim_idx
    ON platform.outbox_event (occurred_at, aggregate_id, aggregate_version)
    WHERE status = 'CLAIMED';

DROP INDEX platform.outbox_event_claimable_idx;
