package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.events.Topics;
import com.shortvideo.social.api.EngagementCounts;
import com.shortvideo.social.api.EngagementReconciler;
import com.shortvideo.social.api.SocialDirectory;
import com.shortvideo.social.domain.SocialService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The like and comment totals through the real pipeline: {@code SocialService} writes the source
 * row and an outbox row in one transaction, the relay publishes it to Kafka, and
 * {@code EngagementCountListener} applies the delta to {@code social.video_engagement}.
 *
 * <p>The other tests call the listener directly with the relay off. This one runs PostgreSQL and
 * Kafka for real, with the application's own relay settings and a three-partition topic as in
 * infrastructure/docker-compose.yml. It asserts what must hold however slow the machine is: after
 * a burst every total equals what was written, nothing was dead-lettered, and a reconciliation
 * sweep finds nothing to correct (so nothing was lost or counted twice).
 *
 * <p>It also prints how long the totals trail the writes, on lines prefixed {@code [PIPELINE]}.
 * Those come from one machine with Docker containers, so they show shape and order of magnitude,
 * not production numbers. Measured that way: a single like is visible after about 220 ms (the
 * relay polls every 500 ms), and a burst of 4,000 writes leaves a backlog of about 8,000 outbox
 * rows that clears in roughly 3 seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class EngagementPipelineIT {

    private static final int WRITERS = 12;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine")
            .withDatabaseName("short_video")
            .withUsername("short_video_app")
            .withPassword("short_video_app");

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // The relay's batch size and poll interval stay at the application's own values.
        registry.add("shortvideo.outbox.enabled", () -> "true");
    }

    @BeforeAll
    static void createTopicWithThreePartitions() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.SOCIAL_EVENTS, 3, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired SocialService social;
    @Autowired SocialDirectory directory;
    @Autowired EngagementReconciler reconciler;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSingleLikeReachesTheTotalWithinTheRelaysPollCycle() throws Exception {
        String video = video(account());
        // The first event pays for consumer-group join and partition assignment, so warm up.
        social.like(video, UUID.randomUUID().toString());
        waitFor(video, 1, Duration.ofSeconds(60));

        List<Long> lagsMs = new ArrayList<>();
        for (int i = 2; i <= 21; i++) {
            long start = System.nanoTime();
            social.like(video, UUID.randomUUID().toString());
            waitFor(video, i, Duration.ofSeconds(30));
            lagsMs.add((System.nanoTime() - start) / 1_000_000);
            Thread.sleep(300); // spread the samples across the relay's poll cycle
        }
        Collections.sort(lagsMs);
        log("single like, write-to-visible lag over %d likes: p50=%d ms max=%d ms",
                lagsMs.size(), lagsMs.get(lagsMs.size() / 2), lagsMs.get(lagsMs.size() - 1));
        assertThat(lagsMs.get(lagsMs.size() - 1)).isLessThan(15_000);
    }

    @Test
    void oneViralVideoUnderABurst() throws Exception {
        burst("one video", List.of(video(account())), 3000, 1000);
    }

    @Test
    void manyVideosUnderABurst() throws Exception {
        String owner = account();
        List<String> videos = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            videos.add(video(owner));
        }
        burst("200 videos", videos, 3000, 1000);
    }

    private void burst(String label, List<String> videos, int likes, int comments) throws Exception {
        // Start from an empty outbox, so one test's backlog does not count against the next.
        long settleBy = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (unpublished() > 0 && System.nanoTime() < settleBy) {
            Thread.sleep(200);
        }
        Thread.sleep(2000); // let the consumers finish what the relay already sent

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        List<Long> writeNanos = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < likes + comments; i++) {
            boolean isLike = i < likes;
            String video = videos.get(ThreadLocalRandom.current().nextInt(videos.size()));
            futures.add(pool.submit(() -> {
                long t = System.nanoTime();
                if (isLike) {
                    social.like(video, UUID.randomUUID().toString());
                } else {
                    social.comment(video, UUID.randomUUID().toString(), "nice one");
                }
                writeNanos.add(System.nanoTime() - t);
            }));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long writesDoneNs = System.nanoTime();
        long backlogWhenWritesDone = unpublished();

        // Wait until the stored totals equal what was written.
        long storedLikes = 0;
        long storedComments = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        while (System.nanoTime() < deadline) {
            storedLikes = 0;
            storedComments = 0;
            for (EngagementCounts c : directory.engagementForAll(videos).values()) {
                storedLikes += c.likeCount();
                storedComments += c.commentCount();
            }
            if (storedLikes == likes && storedComments == comments) {
                break;
            }
            Thread.sleep(50);
        }
        long caughtUpMs = (System.nanoTime() - writesDoneNs) / 1_000_000;

        List<Long> w = new ArrayList<>(writeNanos);
        Collections.sort(w);
        long writeMs = (writesDoneNs - start) / 1_000_000;
        log("%s: %d likes + %d comments written by %d threads in %d ms (%.0f writes/s)",
                label, likes, comments, WRITERS, writeMs, (likes + comments) * 1000.0 / writeMs);
        log("%s: write latency seen by the caller p50=%.1f ms p99=%.1f ms",
                label, w.get(w.size() / 2) / 1e6, w.get((int) (w.size() * 0.99)) / 1e6);
        log("%s: %d outbox rows still unpublished when the writes finished; totals caught up %d ms later",
                label, backlogWhenWritesDone, caughtUpMs);

        assertThat(storedLikes).as("stored likes").isEqualTo(likes);
        assertThat(storedComments).as("stored comments").isEqualTo(comments);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.outbox_event WHERE status = 'DEAD'", Long.class))
                .as("dead outbox rows")
                .isZero();

        // Exactly once: a sweep over every video finds nothing that disagrees with the source tables.
        int corrected = 0;
        String cursor = null;
        do {
            EngagementReconciler.Sweep sweep = reconciler.reconcile(cursor, 1000, Duration.ZERO);
            corrected += sweep.corrected();
            cursor = sweep.lastVideoId();
        } while (cursor != null);
        assertThat(corrected).as("totals that disagreed with the source tables").isZero();
    }

    /** Outbox rows the relay has not yet published (waiting, being retried, or claimed). */
    private long unpublished() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE status IN ('PENDING', 'RETRY', 'CLAIMED')",
                Long.class);
        return n == null ? 0 : n;
    }

    private void waitFor(String video, long likes, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            EngagementCounts c = directory.engagementForAll(List.of(video)).get(video);
            if (c.likeCount() == likes) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the like total for " + video + " did not reach " + likes);
    }

    private static void log(String format, Object... args) {
        System.out.println("[PIPELINE] " + String.format(format, args));
    }

    private String account() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO eligibility.account_eligibility (account_id, account_state, is_account_eligible,"
                        + " source_version, updated_at) VALUES (?, 'ACTIVE', true, 1, now())",
                id);
        return id;
    }

    private String video(String creatorId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO eligibility.video_eligibility (video_id, creator_id, processing_state, durability_state,"
                        + " asset_lifecycle_state, legal_serving_state, moderation_state, publication_state,"
                        + " publication_intent_requested, is_video_eligible, processing_version_source,"
                        + " moderation_version_source, publication_version_source, updated_at)"
                        + " VALUES (?, ?, 'READY', 'DURABLE', 'ACTIVE', 'CLEAR', 'APPROVED', 'PUBLISHED', true, true,"
                        + " 1, 1, 1, now())",
                id,
                creatorId);
        return id;
    }
}
