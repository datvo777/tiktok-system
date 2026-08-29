package com.shortvideo.shared.inbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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

    private static final String INSERT = """
            INSERT INTO platform.consumed_event (consumer_name, event_id, processed_at)
            VALUES (?, ?, ?)
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
        try {
            jdbc.update(INSERT, consumerName, eventId, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
