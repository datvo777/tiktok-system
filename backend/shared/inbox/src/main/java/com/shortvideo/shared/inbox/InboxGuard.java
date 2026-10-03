package com.shortvideo.shared.inbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable consumer inbox (brief section 10, Rule 5).
 *
 * <p>Call {@link #claim} first, inside the same transaction as the business update.
 * A {@code false} return means this event was already applied: acknowledge the
 * Kafka offset without reapplying anything. Commit the database transaction
 * before acknowledging the offset.
 */
@Component
public class InboxGuard {

    /**
     * {@code ON CONFLICT DO NOTHING} rather than catching {@code DuplicateKeyException}.
     *
     * <p>In PostgreSQL a statement that raises an error aborts the whole
     * transaction: every subsequent statement fails with "current transaction is
     * aborted" until rollback. Letting the insert throw and swallowing the
     * exception therefore left the caller holding a transaction that looked healthy
     * but could no longer do anything — which is exactly the shape this class's own
     * contract invites, since it tells callers to claim inside the transaction that
     * applies the business update. Every listener happens to return immediately on
     * {@code false} today, so nothing was broken; the next one to do anything else
     * would have hit a confusing database error instead of a clear one.
     *
     * <p>Conflicting rows simply affect zero rows, so the transaction stays usable.
     */
    private static final String INSERT = """
            INSERT INTO platform.consumed_event (consumer_name, event_id, processed_at)
            VALUES (?, ?, ?)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public InboxGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this consumer has not seen the event before. */
    public boolean claim(String consumerName, UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "InboxGuard.claim must run inside the transaction that applies the "
                            + "business update (brief section 10)");
        }
        // Zero rows means the conflict target already held this event, i.e. this
        // consumer has seen it before.
        return jdbc.update(INSERT, consumerName, eventId, Timestamp.from(Instant.now())) == 1;
    }
}
