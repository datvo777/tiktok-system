package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shortvideo.shared.outbox.OutboxBacklogGauge;
import com.shortvideo.shared.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The backlog-age reading against the real table: which rows count as waiting, and that the app
 * exposes it for an alert to read.
 *
 * <p>The database is shared with other tests, so the rows here are dated years before anything else
 * and the oldest one is therefore always one of ours.
 */
class OutboxBacklogMetricIT extends AbstractDatabaseIT {

    private static final String AGGREGATE_TYPE = "BACKLOG_IT";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    MeterRegistry appMeters;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM platform.outbox_event WHERE aggregate_type = ?", AGGREGATE_TYPE);
    }

    @Test
    void ageIsThatOfTheOldestEventStillWaiting() {
        Instant now = Instant.now();
        // Older than the waiting ones, but not waiting: sent, dead, or held by a relay that is alive.
        insert("PUBLISHED", now.minus(Duration.ofDays(4000)), null);
        insert("DEAD", now.minus(Duration.ofDays(3900)), null);
        insert("CLAIMED", now.minus(Duration.ofDays(3800)), now.plus(Duration.ofMinutes(1)));
        assertThat(outbox.oldestWaitingSeconds()).isLessThan(Duration.ofDays(3000).toSeconds());

        insert("PENDING", now.minus(Duration.ofDays(2000)), null);
        assertThat(outbox.oldestWaitingSeconds()).isCloseTo(Duration.ofDays(2000).toSeconds(), within(30.0));

        insert("RETRY", now.minus(Duration.ofDays(2100)), null);
        assertThat(outbox.oldestWaitingSeconds()).isCloseTo(Duration.ofDays(2100).toSeconds(), within(30.0));

        // A relay that died mid-batch leaves its rows CLAIMED; once the lease is out they are waiting again.
        insert("CLAIMED", now.minus(Duration.ofDays(2200)), now.minus(Duration.ofMinutes(1)));
        assertThat(outbox.oldestWaitingSeconds()).isCloseTo(Duration.ofDays(2200).toSeconds(), within(30.0));
    }

    @Test
    void theAppExposesTheGauge() {
        assertThat(appMeters.find(OutboxBacklogGauge.NAME).gauge()).isNotNull();
    }

    private void insert(String status, Instant occurredAt, Instant claimedUntil) {
        Timestamp occurred = Timestamp.from(occurredAt);
        boolean claimed = claimedUntil != null;
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status, claimed_by, claim_token, claimed_until
                ) VALUES (?, ?, ?, 'backlog.it', 1, 1, '{}'::jsonb, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                AGGREGATE_TYPE,
                UUID.randomUUID().toString(),
                occurred,
                occurred,
                status,
                claimed ? "relay-other" : null,
                claimed ? UUID.randomUUID() : null,
                claimed ? Timestamp.from(claimedUntil) : null);
    }
}
