package com.shortvideo.shared.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes {@code platform.outbox_event} rows that finished successfully.
 *
 * <p>Nothing did this before: {@link OutboxRepository} only claims, finalises
 * and fails rows, so every event ever published stayed in the table forever.
 * That never produced wrong behaviour — {@code outbox_event_claimable_idx} is
 * a partial index that already excludes {@code PUBLISHED} rows, so the
 * relay's own claim query stays fast regardless of table size — but the
 * primary key, the {@code (aggregate_type, aggregate_id, aggregate_version)}
 * unique index and the {@code payload} column all grow without bound, which
 * is unnecessary disk, backup and autovacuum cost with no corresponding
 * benefit past a retention window generous enough for manual investigation.
 *
 * <p>Only {@code PUBLISHED} rows are pruned. {@code DEAD} rows are a producer
 * -side terminal failure — this relay could not get the event onto Kafka at
 * all after exhausting every attempt — and are left for an operator to triage
 * (see {@code outbox_event_dead_idx}); nothing here removes them
 * automatically.
 *
 * <p>Unlike {@link com.shortvideo.shared.inbox.InboxCleanupJob}, this
 * retention is not bound to the Kafka broker's topic retention — the outbox
 * is not a source Kafka redelivers into, so there is no correctness floor on
 * the window, only an operational one (how long a published event should
 * stay queryable for audit). The default here is deliberately much longer
 * than the inbox's for that reason.
 */
@Component
class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);
    private static final int BATCH_LIMIT = 5000;

    private static final String DELETE_BATCH = """
            DELETE FROM platform.outbox_event
            WHERE ctid IN (
                SELECT ctid FROM platform.outbox_event
                WHERE status = 'PUBLISHED' AND published_at < ?
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final Counter deletedCounter;

    OutboxCleanupJob(
            JdbcTemplate jdbc,
            MeterRegistry meterRegistry,
            @Value("${shortvideo.outbox.cleanup-retention:720h}") String retention) {
        this.jdbc = jdbc;
        this.retention = DurationStyle.detectAndParse(retention);
        this.deletedCounter = Counter.builder("outbox.cleanup.rows_deleted").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${shortvideo.outbox.cleanup-interval:1h}", initialDelayString = "3m")
    void sweep() {
        Instant cutoff = Instant.now().minus(retention);
        int totalDeleted = 0;
        int deletedThisBatch;
        do {
            deletedThisBatch = jdbc.update(DELETE_BATCH, Timestamp.from(cutoff), BATCH_LIMIT);
            totalDeleted += deletedThisBatch;
        } while (deletedThisBatch == BATCH_LIMIT);

        if (totalDeleted > 0) {
            deletedCounter.increment(totalDeleted);
            log.info("Outbox cleanup removed {} published event row{} older than {}", totalDeleted, totalDeleted == 1 ? "" : "s", cutoff);
        }
    }
}
