package com.shortvideo.shared.inbox;

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
 * Prunes {@code platform.consumed_event} (brief section 20, Milestone 8:
 * "inbox cleanup preserves the supported Kafka replay window").
 *
 * <p>The retention window here must stay strictly longer than the Kafka
 * broker's own topic retention (this stack's default is 168h/7 days — see
 * {@code infrastructure/docker-compose.yml}, which sets no override). If a
 * consumed-event row were pruned while Kafka could still redeliver that
 * offset, a legitimate redelivery within the replay window would find no
 * inbox row and reapply its business effect a second time — exactly what
 * Rule 5's dedup exists to prevent. The default here (240h/10 days) keeps a
 * deliberate safety margin over the broker default rather than matching it
 * exactly.
 */
@Component
class InboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(InboxCleanupJob.class);
    private static final int BATCH_LIMIT = 5000;

    private static final String DELETE_BATCH = """
            DELETE FROM platform.consumed_event
            WHERE ctid IN (
                SELECT ctid FROM platform.consumed_event
                WHERE processed_at < ?
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final Counter deletedCounter;

    InboxCleanupJob(
            JdbcTemplate jdbc,
            MeterRegistry meterRegistry,
            @Value("${shortvideo.inbox.retention:240h}") String retention) {
        this.jdbc = jdbc;
        this.retention = DurationStyle.detectAndParse(retention);
        this.deletedCounter = Counter.builder("inbox.cleanup.rows_deleted").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${shortvideo.inbox.cleanup-interval:1h}", initialDelayString = "1m")
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
            log.info("Inbox cleanup removed {} consumed-event row{} older than {}", totalDeleted, totalDeleted == 1 ? "" : "s", cutoff);
        }
    }
}
