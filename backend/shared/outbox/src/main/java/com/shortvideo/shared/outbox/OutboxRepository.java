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
     */
    private static final String CLAIM = """
            WITH selected AS (
                SELECT event_id
                FROM platform.outbox_event
                WHERE (
                    status IN ('PENDING', 'RETRY')
                    OR (status = 'CLAIMED' AND claimed_until < now())
                )
                  AND available_at <= now()
                ORDER BY occurred_at, aggregate_id, aggregate_version
                FOR UPDATE SKIP LOCKED
                LIMIT ?
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
        return jdbc.query(CLAIM, MAPPER, batchSize, relayId, claimToken, (double) leaseSeconds);
    }

    /** @return true when this relay still owned the claim and the row was finalised. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, UUID claimToken) {
        return jdbc.update(FINALISE, eventId, claimToken) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID eventId, UUID claimToken, String error, Instant retryAt, boolean dead) {
        String status = dead ? "DEAD" : "RETRY";
        String sanitised = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        return jdbc.update(FAIL, status, sanitised, Timestamp.from(retryAt), eventId, claimToken) == 1;
    }
}
