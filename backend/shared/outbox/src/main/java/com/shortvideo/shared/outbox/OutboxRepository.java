package com.shortvideo.shared.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claim/finalise operations for the relay (brief section 10). */
@Repository
public class OutboxRepository {

    /**
     * Claim in one short transaction. SKIP LOCKED means a claimer never waits on a
     * contended row, so this cannot deadlock; every row the outer UPDATE touches is
     * already locked by this same transaction through the CTE.
     *
     * <p>occurred_at alone is not a total order — two events can share a timestamp —
     * so ties break on (aggregate_id, aggregate_version).
     *
     * <p>Picking the rows takes two steps so that its cost follows the batch size, not the backlog.
     * {@code candidates} reads the first {@code n} rows of each source (ready, and expired
     * leases) in index order and stops there; {@code top} keeps the first {@code n} of those.
     * Written as one query with an OR, the planner has to read and sort every claimable row first
     * (see V49). Only then does {@code selected} lock the rows, because FOR UPDATE cannot be applied
     * to a UNION.
     *
     * <p>{@code selected} looks each of the {@code n} rows up by primary key (LATERAL) instead of
     * joining {@code top} to the table. The outbox is nearly empty most of the time, so when
     * PostgreSQL last analysed it almost every row was PUBLISHED and it recorded that nothing else
     * was there. A burst adds thousands of pending rows before the next analyse, and a plain join
     * planned from those figures expects about one claimable row: it read every claimable row and
     * re-ran the choice of {@code top} for each (800,000 comparisons, 139 ms standalone, 0.3 to 1 s
     * in a pipeline test) until the next automatic analyse, 13 to 58 s later. The lookup cannot be
     * planned any other way, so the claim costs the same whatever the statistics say.
     *
     * <p>{@code selected} repeats the state test: a row can be claimed by someone else between
     * the two steps, and re-checking is what stops it being claimed twice. Because the lock is
     * taken after the choice, a claimer that finds some of its rows locked returns fewer than
     * {@code n} rows rather than reaching past them. With one relay that never happens; with several
     * the relay's "fewer than a full batch, so stop" test would stop it early and it would pick up
     * again at the next poll.
     */
    private static final String CLAIM = """
            WITH candidates AS (
                (SELECT event_id, occurred_at, aggregate_id, aggregate_version
                 FROM platform.outbox_event
                 WHERE status IN ('PENDING', 'RETRY') AND available_at <= now()
                 ORDER BY occurred_at, aggregate_id, aggregate_version
                 LIMIT ?)
                UNION ALL
                (SELECT event_id, occurred_at, aggregate_id, aggregate_version
                 FROM platform.outbox_event
                 WHERE status = 'CLAIMED' AND claimed_until < now() AND available_at <= now()
                 ORDER BY occurred_at, aggregate_id, aggregate_version
                 LIMIT ?)
            ),
            top AS (
                SELECT event_id
                FROM candidates
                ORDER BY occurred_at, aggregate_id, aggregate_version
                LIMIT ?
            ),
            selected AS (
                SELECT o.event_id
                FROM top
                CROSS JOIN LATERAL (
                    SELECT o.event_id
                    FROM platform.outbox_event o
                    WHERE o.event_id = top.event_id
                      AND (
                          o.status IN ('PENDING', 'RETRY')
                          OR (o.status = 'CLAIMED' AND o.claimed_until < now())
                      )
                      AND o.available_at <= now()
                    FOR UPDATE OF o SKIP LOCKED
                ) o
            )
            UPDATE platform.outbox_event o
            SET status = 'CLAIMED',
                claimed_by = ?,
                claim_token = ?,
                claimed_until = now() + make_interval(secs => ?),
                attempt_count = attempt_count + 1,
                last_attempt_at = now()
            FROM selected
            WHERE o.event_id = selected.event_id
            RETURNING o.event_id, o.aggregate_type, o.aggregate_id, o.event_type,
                      o.schema_version, o.aggregate_version, o.payload::text AS payload,
                      o.occurred_at, o.attempt_count, o.claim_token
            """;

    private static final String FINALISE = """
            UPDATE platform.outbox_event
            SET status = 'PUBLISHED', published_at = now(), claimed_by = NULL,
                claim_token = NULL, claimed_until = NULL
            WHERE event_id = ? AND status = 'CLAIMED' AND claim_token = ?
            """;

    private static final String FAIL = """
            UPDATE platform.outbox_event
            SET status = ?, last_error = ?, available_at = ?,
                claimed_by = NULL, claim_token = NULL, claimed_until = NULL
            WHERE event_id = ? AND status = 'CLAIMED' AND claim_token = ?
            """;

    private static final String FINALISE_BATCH = """
            UPDATE platform.outbox_event
            SET status = 'PUBLISHED', published_at = now(), claimed_by = NULL,
                claim_token = NULL, claimed_until = NULL
            WHERE event_id = ANY (?) AND status = 'CLAIMED' AND claim_token = ?
            """;

    /**
     * Gives back rows this relay claimed but deliberately did not send, without counting an attempt
     * (the claim already incremented it): they were held behind an earlier event of the same
     * aggregate that failed.
     */
    private static final String RELEASE = """
            UPDATE platform.outbox_event
            SET status = 'RETRY', available_at = ?, attempt_count = attempt_count - 1,
                claimed_by = NULL, claim_token = NULL, claimed_until = NULL
            WHERE event_id = ANY (?) AND status = 'CLAIMED' AND claim_token = ?
            """;

    /**
     * A claim whose lease ran out with the attempt budget already spent: the relay claimed this row
     * that many times and never got as far as recording an outcome, which is what a payload that
     * kills the relay looks like. {@code recordFailure} cannot catch it because it only runs in a
     * relay that survived the send.
     */
    private static final String BURY_ABANDONED = """
            UPDATE platform.outbox_event
            SET status = 'DEAD',
                last_error = 'relay claimed this event ' || attempt_count || ' times without recording an outcome',
                claimed_by = NULL, claim_token = NULL, claimed_until = NULL
            WHERE status = 'CLAIMED' AND claimed_until < now() AND attempt_count >= ?
            """;

    private static final RowMapper<OutboxRecord> MAPPER = (rs, i) -> new OutboxRecord(
            rs.getObject("event_id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getInt("schema_version"),
            rs.getLong("aggregate_version"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getInt("attempt_count"),
            rs.getObject("claim_token", UUID.class));

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxRecord> claimBatch(String relayId, UUID claimToken, int batchSize, long leaseSeconds) {
        // Plan every claim for the table as it is now. The relay polls every 500 ms whether or not
        // anything is waiting, so the driver's prepared statement runs many times against an empty
        // outbox, and the server then reuses a plan made for that: a sequential scan under a nested
        // loop, cheap on an empty table and 3.2 s once 8,000 rows are pending (1.5 ms when planned
        // for that table). Planning costs about 0.2 ms, once per poll. LOCAL ends with this
        // transaction, so nothing else on the connection is affected.
        jdbc.execute("SET LOCAL plan_cache_mode = force_custom_plan");
        // batchSize bounds each of the three LIMITs in CLAIM.
        return jdbc.query(CLAIM, MAPPER, batchSize, batchSize, batchSize, relayId, claimToken, (double) leaseSeconds);
    }

    /** @return true when this relay still owned the claim and the row was finalised. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, UUID claimToken) {
        return jdbc.update(FINALISE, eventId, claimToken) == 1;
    }

    /**
     * One statement for everything the broker acknowledged, rather than a round trip per event.
     * The claim-token check still applies to every row.
     *
     * @return how many rows this relay still owned and finalised; fewer than {@code eventIds.size()}
     *     means some claims were lost to another relay
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markPublishedBatch(List<UUID> eventIds, UUID claimToken) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement(FINALISE_BATCH);
            statement.setArray(1, connection.createArrayOf("uuid", eventIds.toArray()));
            statement.setObject(2, claimToken);
            return statement;
        });
    }

    /**
     * Returns held-back rows to the queue, available again at {@code availableAt}: the same moment as
     * the failed event they were held behind, so the two come back together and in order.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int release(List<UUID> eventIds, UUID claimToken, Instant availableAt) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement(RELEASE);
            statement.setTimestamp(1, Timestamp.from(availableAt));
            statement.setArray(2, connection.createArrayOf("uuid", eventIds.toArray()));
            statement.setObject(3, claimToken);
            return statement;
        });
    }

    /**
     * Moves events to DEAD that the relay keeps claiming but never settles (see {@link #BURY_ABANDONED}).
     * Run before each claim, so such a row is stopped rather than claimed once more.
     *
     * @return how many rows were buried
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int buryAbandoned(int maxAttempts) {
        return jdbc.update(BURY_ABANDONED, maxAttempts);
    }

    /** Events the relay gave up on; served by the partial dead index. */
    public long countDead() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM platform.outbox_event WHERE status = 'DEAD'", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Age in seconds of the oldest event that is waiting to be sent: ready or retrying, or claimed
     * with the lease already run out (a relay that died mid-batch leaves those behind). The two
     * halves are the claim's two sources, each read from the first entry of its own index.
     * Measured against the database clock, like the claim.
     *
     * @return 0 when nothing is waiting
     */
    public double oldestWaitingSeconds() {
        Double age = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(oldest)), 0)::float8
                FROM (
                    SELECT min(occurred_at) AS oldest
                    FROM platform.outbox_event
                    WHERE status IN ('PENDING', 'RETRY')
                    UNION ALL
                    SELECT min(occurred_at)
                    FROM platform.outbox_event
                    WHERE status = 'CLAIMED' AND claimed_until < now()
                ) sources
                """, Double.class);
        return age == null ? 0 : Math.max(0, age);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID eventId, UUID claimToken, String error, Instant retryAt, boolean dead) {
        String status = dead ? "DEAD" : "RETRY";
        String sanitised = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        return jdbc.update(FAIL, status, sanitised, Timestamp.from(retryAt), eventId, claimToken) == 1;
    }
}
