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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a claim picks, against the real table: the oldest claimable rows first whichever source they
 * come from, never a row that is not due, and never the same row twice.
 *
 * <p>The database is shared with other tests, so every row here is dated far in the past: they sort
 * ahead of anything else, and a batch never reaches a row it does not own.
 */
class OutboxClaimIT extends AbstractDatabaseIT {

    private static final String AGGREGATE_TYPE = "CLAIM_IT";
    private static final Instant EPOCH = Instant.parse("2000-01-01T00:00:00Z");
    private static final long LEASE_SECONDS = 60;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRepository outbox;

    @Value("${spring.datasource.url}")
    String url;

    @Value("${spring.datasource.username}")
    String username;

    @Value("${spring.datasource.password}")
    String password;

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

    /**
     * The relay polls every 500 ms whether or not anything is waiting, so the driver's prepared
     * claim statement is run many times against an empty outbox, and the server then settles on a
     * plan it reuses for that connection. That plan (a sequential scan joined with a nested loop)
     * is cheap on an empty table and ruinous on a large one: measured on PostgreSQL 17.4 it took
     * 3.2 s once 8,000 rows were pending, where a plan made for that table took 1.5 ms. In a
     * pipeline test it showed as claims of 130 to 370 ms that held the relay to about 200 rows a
     * second. A claim has to be planned for the table it finds.
     *
     * <p>The setting only lives inside the claim's transaction, so the test runs the claim inside a
     * transaction of its own and reads it back before rolling everything back.
     */
    @Test
    void aClaimIsPlannedForTheTableItFindsNotFromAPlanCachedWhileTheOutboxWasEmpty() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, username, password, true);
        try {
            JdbcTemplate template = new JdbcTemplate(dataSource);
            OutboxRepository repository = new OutboxRepository(template);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

            String planCacheMode = transaction.execute(status -> {
                repository.claimBatch("relay-it", UUID.randomUUID(), 1, LEASE_SECONDS);
                status.setRollbackOnly(); // the claim may have taken someone else's row; keep none of it
                return template.queryForObject("SHOW plan_cache_mode", String.class);
            });

            assertThat(planCacheMode).isEqualTo("force_custom_plan");
        } finally {
            dataSource.destroy();
        }
    }

    /**
     * The outbox is empty or nearly so most of the time, so when PostgreSQL last analysed it, almost
     * every row was PUBLISHED and it recorded that nothing else was there. A burst then adds
     * thousands of pending rows before the next analyse, and a claim planned from those figures
     * expects about one claimable row. It joined the 100 chosen rows to every claimable row with a
     * nested loop that re-ran the whole choice for each of them: 800,000 comparisons, 139 ms in a
     * standalone database and 300 ms to 1 s in a pipeline test, held there for 13 to 58 seconds until
     * the next automatic analyse. A claim must cost the same whatever the statistics say.
     */
    @Test
    void aClaimStaysFastWhenTheStatisticsSayNothingIsWaiting() {
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status, published_at
                )
                SELECT gen_random_uuid(), ?, gen_random_uuid()::text, 'claim.it', 1, 1, '{}'::jsonb,
                       ?::timestamptz + n * interval '1 millisecond', now() - interval '1 hour', 'PUBLISHED', now()
                FROM generate_series(1, 100000) n
                """,
                AGGREGATE_TYPE,
                Timestamp.from(EPOCH));
        jdbc.execute("ANALYZE platform.outbox_event");
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status
                )
                SELECT gen_random_uuid(), ?, gen_random_uuid()::text, 'claim.it', 1, 1, '{}'::jsonb,
                       ?::timestamptz + n * interval '1 millisecond', now() - interval '1 hour', 'PENDING'
                FROM generate_series(1, 20000) n
                """,
                AGGREGATE_TYPE,
                Timestamp.from(EPOCH));

        long slowestMs = 0;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            List<OutboxRecord> claimed = outbox.claimBatch("relay-it", UUID.randomUUID(), 100, LEASE_SECONDS);
            slowestMs = Math.max(slowestMs, (System.nanoTime() - start) / 1_000_000);
            assertThat(claimed).hasSize(100);
        }

        assertThat(slowestMs).as("slowest of three claims, in ms").isLessThan(100);
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
