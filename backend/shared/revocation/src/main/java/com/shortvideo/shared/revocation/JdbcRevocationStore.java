package com.shortvideo.shared.revocation;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class JdbcRevocationStore implements DurableRevocationWriter, DurableRevocationReader {

    private static final String UPSERT = """
            INSERT INTO platform.revocation (
                subject_type, subject_id, source_type, source_version, active,
                reason, blocking_version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, true, ?, ?, ?, ?)
            ON CONFLICT (subject_type, subject_id, source_type) DO UPDATE SET
                source_version = EXCLUDED.source_version,
                active = true,
                reason = EXCLUDED.reason,
                blocking_version = EXCLUDED.blocking_version,
                updated_at = EXCLUDED.updated_at,
                cleared_at = NULL
            WHERE platform.revocation.source_version < EXCLUDED.source_version
            """;

    private static final String CLEAR = """
            UPDATE platform.revocation
            SET active = false, cleared_at = ?, updated_at = ?
            WHERE subject_type = ? AND subject_id = ? AND source_type = ?
              AND active = true AND blocking_version = ?
            """;

    private static final String IS_ACTIVE = """
            SELECT EXISTS (
                SELECT 1 FROM platform.revocation
                WHERE subject_type = ? AND subject_id = ? AND active = true
            )
            """;

    /**
     * Uses the same {@code (subject_type, subject_id) WHERE active} partial index as
     * {@link #IS_ACTIVE}; {@code = ANY(?)} keeps the statement text constant
     * regardless of how many subjects are asked about.
     */
    private static final String ACTIVE_AMONG = """
            SELECT DISTINCT subject_id FROM platform.revocation
            WHERE subject_type = ? AND active = true AND subject_id = ANY(?)
            """;

    /**
     * Keyset pages over the primary key, so the rebuild never holds more than one page: the order is
     * the key's own, which also keeps every source of one subject adjacent.
     */
    private static final String FIRST_ACTIVE_PAGE = """
            SELECT subject_type, subject_id, source_type, reason FROM platform.revocation
            WHERE active = true
            ORDER BY subject_type, subject_id, source_type
            LIMIT ?
            """;

    private static final String NEXT_ACTIVE_PAGE = """
            SELECT subject_type, subject_id, source_type, reason FROM platform.revocation
            WHERE active = true AND (subject_type, subject_id, source_type) > (?, ?, ?)
            ORDER BY subject_type, subject_id, source_type
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final RevocationCache cache;

    public JdbcRevocationStore(JdbcTemplate jdbc, RevocationCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Override
    public void activate(RevocationCommand command) {
        requireTransaction();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                UPSERT,
                command.subjectType(),
                command.subjectId(),
                command.sourceType(),
                command.sourceVersion(),
                command.reason(),
                command.sourceVersion(),
                now,
                now);
        afterCommit(() -> cache.putActive(
                command.subjectType(), command.subjectId(), command.sourceType(), command.reason()));
    }

    @Override
    public void clear(RevocationClearCommand command) {
        requireTransaction();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                CLEAR,
                now,
                now,
                command.subjectType(),
                command.subjectId(),
                command.sourceType(),
                command.expectedBlockingVersion());
        afterCommit(() -> cache.clearField(command.subjectType(), command.subjectId(), command.sourceType()));
    }

    @Override
    public boolean isActive(String subjectType, String subjectId) {
        Boolean active = jdbc.queryForObject(IS_ACTIVE, Boolean.class, subjectType, subjectId);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public java.util.Set<String> activeAmong(String subjectType, java.util.Collection<String> subjectIds) {
        if (subjectIds.isEmpty()) {
            return java.util.Set.of();
        }
        // subject_id is VARCHAR here (it holds both video and account ids), so the
        // array is bound as text rather than uuid.
        String[] ids = subjectIds.toArray(String[]::new);
        return new java.util.HashSet<>(jdbc.queryForList(ACTIVE_AMONG, String.class, subjectType, ids));
    }

    /**
     * Brief section 16: "After a Redis restart, rebuild hashes from active durable revocations."
     * One page of them, in key order, strictly after {@code after} (the start when null).
     */
    java.util.List<ActiveRevocation> findActivePage(ActiveRevocation after, int limit) {
        org.springframework.jdbc.core.RowMapper<ActiveRevocation> mapper = (rs, rowNum) -> new ActiveRevocation(
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getString("source_type"),
                rs.getString("reason"));
        if (after == null) {
            return jdbc.query(FIRST_ACTIVE_PAGE, mapper, limit);
        }
        return jdbc.query(
                NEXT_ACTIVE_PAGE, mapper, after.subjectType(), after.subjectId(), after.sourceType(), limit);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "DurableRevocationWriter must run inside the transaction that writes the "
                            + "authoritative state (brief section 9)");
        }
    }

    /** Redis update happens immediately after commit — never inside the DB transaction. */
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
