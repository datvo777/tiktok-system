package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.outbox.OutboxRecord;
import com.shortvideo.shared.outbox.OutboxRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a claim picks, against the real table: the oldest claimable rows first whichever source they
 * come from, never a row that is not due, and never the same row twice.
 *
 * <p>The database is shared with other tests, so every row here is dated far in the past: they sort
 * ahead of anything else, and a batch never reaches a row it does not own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OutboxClaimIT {

    private static final String AGGREGATE_TYPE = "CLAIM_IT";
    private static final Instant EPOCH = Instant.parse("2000-01-01T00:00:00Z");
    private static final long LEASE_SECONDS = 60;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRepository outbox;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM platform.outbox_event WHERE aggregate_type = ?", AGGREGATE_TYPE);
    }

    @Test
    void takesTheOldestClaimableRowsWhicheverSourceTheyComeFrom() {
        // Older than all of these, but not claimable.
        UUID notDueYet = insert("d", 1, "RETRY", null, 0, Duration.ofHours(1));
        UUID stillLeased = insert("e", 1, "CLAIMED", Duration.ofMinutes(1), 0, Duration.ofHours(-1));
        UUID expiredLease = insert("a", 1, "CLAIMED", Duration.ofMinutes(-1), 1, Duration.ofHours(-1));
        UUID retry = insert("b", 1, "RETRY", null, 2, Duration.ofHours(-1));
        UUID pending = insert("c", 1, "PENDING", null, 3, Duration.ofHours(-1));

        List<OutboxRecord> twoOldest = outbox.claimBatch("relay-it", UUID.randomUUID(), 2, LEASE_SECONDS);

        assertThat(ids(twoOldest)).containsExactlyInAnyOrder(expiredLease, retry);
        assertThat(status(pending)).isEqualTo("PENDING");

        List<OutboxRecord> rest = outbox.claimBatch("relay-it", UUID.randomUUID(), 1, LEASE_SECONDS);

        assertThat(ids(rest)).containsExactly(pending);
        assertThat(status(notDueYet)).isEqualTo("RETRY");
        assertThat(status(stillLeased)).isEqualTo("CLAIMED");
        assertThat(attempts(notDueYet)).isZero();
    }

    @Test
    void claimingCountsAnAttemptAndStampsTheLease() {
        UUID reclaimed = insert("a", 1, "CLAIMED", Duration.ofMinutes(-1), 1, Duration.ofHours(-1));
        UUID fresh = insert("b", 1, "PENDING", null, 2, Duration.ofHours(-1));
        UUID token = UUID.randomUUID();

        List<OutboxRecord> claimed = outbox.claimBatch("relay-it", token, 2, LEASE_SECONDS);

        assertThat(ids(claimed)).containsExactlyInAnyOrder(reclaimed, fresh);
        assertThat(claimed).allSatisfy(record -> assertThat(record.claimToken()).isEqualTo(token));
        assertThat(attempts(reclaimed)).isEqualTo(2);
        assertThat(attempts(fresh)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT claimed_until > now() FROM platform.outbox_event WHERE event_id = ?",
                        Boolean.class,
                        fresh))
                .isTrue();
    }

    @Test
    void sameTimestampBreaksTiesOnAggregateThenVersion() {
        UUID laterAggregate = insert("b", 1, "PENDING", null, 1, Duration.ofHours(-1));
        UUID laterVersion = insert("a", 2, "PENDING", null, 1, Duration.ofHours(-1));
        UUID first = insert("a", 1, "PENDING", null, 1, Duration.ofHours(-1));

        assertThat(ids(outbox.claimBatch("relay-it", UUID.randomUUID(), 1, LEASE_SECONDS))).containsExactly(first);
        assertThat(ids(outbox.claimBatch("relay-it", UUID.randomUUID(), 1, LEASE_SECONDS)))
                .containsExactly(laterVersion);
        assertThat(ids(outbox.claimBatch("relay-it", UUID.randomUUID(), 1, LEASE_SECONDS)))
                .containsExactly(laterAggregate);
    }

    @Test
    void concurrentClaimersNeverTakeTheSameRow() throws Exception {
        int rows = 400;
        int claimers = 4;
        int batch = 25;
        int claimsEach = 3; // 300 of the 400 at most, so a batch always has its own rows to fill it
        Set<UUID> mine = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            mine.add(insert("agg-" + (i % 50), i + 1, "PENDING", null, i, Duration.ofHours(-1)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(claimers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<UUID>>> results = new ArrayList<>();
        for (int c = 0; c < claimers; c++) {
            results.add(pool.submit(() -> {
                start.await();
                List<UUID> claimed = new ArrayList<>();
                for (int i = 0; i < claimsEach; i++) {
                    claimed.addAll(ids(outbox.claimBatch("relay-it", UUID.randomUUID(), batch, LEASE_SECONDS)));
                }
                return claimed;
            }));
        }
        start.countDown();
        List<UUID> all = new ArrayList<>();
        for (Future<List<UUID>> result : results) {
            all.addAll(result.get());
        }
        pool.shutdown();

        assertThat(all).isNotEmpty().doesNotHaveDuplicates();
        assertThat(mine).containsAll(all);
        // What it handed out is exactly what is now claimed.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_event WHERE aggregate_type = ? AND status = 'CLAIMED'",
                        Long.class,
                        AGGREGATE_TYPE))
                .isEqualTo((long) all.size());
    }

    @Test
    void drainingInTurnClaimsEveryRowOnce() {
        int rows = 120;
        Set<UUID> mine = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            mine.add(insert("agg-" + (i % 7), i + 1, "PENDING", null, i, Duration.ofHours(-1)));
        }

        List<UUID> claimed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            claimed.addAll(ids(outbox.claimBatch("relay-it", UUID.randomUUID(), 30, LEASE_SECONDS)));
        }

        assertThat(claimed).doesNotHaveDuplicates().hasSize(rows);
        assertThat(new HashSet<>(claimed)).isEqualTo(mine);
    }

    private static List<UUID> ids(List<OutboxRecord> records) {
        return records.stream().map(OutboxRecord::eventId).toList();
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM platform.outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT attempt_count FROM platform.outbox_event WHERE event_id = ?", Integer.class, eventId);
    }

    /**
     * @param leaseEnds when a CLAIMED row's lease ends, relative to now; null for rows nobody holds
     * @param secondsAfterEpoch the event's occurred_at, so lower sorts earlier
     * @param availableIn when the row becomes due, relative to now (negative: already due)
     */
    private UUID insert(
            String aggregateId,
            long version,
            String status,
            Duration leaseEnds,
            long secondsAfterEpoch,
            Duration availableIn) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        boolean claimed = "CLAIMED".equals(status);
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status, attempt_count, claimed_by, claim_token, claimed_until
                ) VALUES (?, ?, ?, 'claim.it', 1, ?, '{}'::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                AGGREGATE_TYPE,
                aggregateId,
                version,
                Timestamp.from(EPOCH.plusSeconds(secondsAfterEpoch)),
                Timestamp.from(now.plus(availableIn)),
                status,
                claimed ? 1 : 0,
                claimed ? "relay-other" : null,
                claimed ? UUID.randomUUID() : null,
                leaseEnds == null ? null : Timestamp.from(now.plus(leaseEnds)));
        return eventId;
    }
}
