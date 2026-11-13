package com.shortvideo.shared.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.app.Application;
import com.shortvideo.app.TestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The cache rebuild against real PostgreSQL and Redis, with a page size of two so that every case
 * crosses page boundaries. In this package only because the rebuilder is package-private.
 *
 * <p>What it must still do after being made to read a page at a time: write every active
 * revocation, drop fields a subject no longer has (including for a subject whose sources are split
 * across two pages), and drop whole subjects that have none left.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RevocationCacheRebuilderIT {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.revocation.rebuild-page-size", () -> "2");
        // The test drives the rebuild itself; the clock should not run one under it.
        registry.add("shortvideo.revocation.rebuild-interval", () -> "1h");
    }

    @Autowired RevocationCacheRebuilder rebuilder;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM platform.revocation WHERE subject_id LIKE ?", RUN + "-%");
        redis.keys("revocation:*:" + RUN + "-*").forEach(redis::delete);
    }

    @Test
    void everyActiveRevocationIsCachedAcrossPages() {
        // Five rows for one subject-type, three of them one subject: with pages of two, that
        // subject straddles a page boundary whatever else is in the table.
        insert("VIDEO", id("a"), "MODERATION", true);
        insert("VIDEO", id("a"), "APPEAL", true);
        insert("VIDEO", id("a"), "PUBLICATION", true);
        insert("VIDEO", id("b"), "MODERATION", true);
        insert("ACCOUNT", id("c"), "SUSPENSION", true);

        rebuilder.periodicRebuild();

        assertThat(fields("video", id("a"))).containsExactlyInAnyOrder("MODERATION", "APPEAL", "PUBLICATION");
        assertThat(fields("video", id("b"))).containsExactly("MODERATION");
        assertThat(fields("account", id("c"))).containsExactly("SUSPENSION");
    }

    @Test
    void aFieldTheSubjectNoLongerHasIsRemovedEvenWhenItsSourcesSpanTwoPages() {
        insert("VIDEO", id("a"), "MODERATION", true);
        insert("VIDEO", id("a"), "APPEAL", true);
        insert("VIDEO", id("a"), "PUBLICATION", true);
        // A permissive change whose cache update was lost: durable says cleared, Redis still says so.
        insert("VIDEO", id("a"), "REPORT", false);
        redis.opsForHash().put(cacheKey("video", id("a")), "REPORT", "stale");

        rebuilder.periodicRebuild();

        assertThat(fields("video", id("a"))).containsExactlyInAnyOrder("MODERATION", "APPEAL", "PUBLICATION");
    }

    @Test
    void aCachedSubjectWithNothingActiveLeftIsDropped() {
        insert("VIDEO", id("cleared"), "MODERATION", false);
        redis.opsForHash().put(cacheKey("video", id("cleared")), "MODERATION", "stale");
        redis.opsForHash().put(cacheKey("video", id("unknown")), "MODERATION", "stale");

        rebuilder.periodicRebuild();

        assertThat(redis.hasKey(cacheKey("video", id("cleared")))).isFalse();
        assertThat(redis.hasKey(cacheKey("video", id("unknown")))).isFalse();
    }

    @Test
    void anActiveSubjectIsNotDroppedByTheStrayKeyPass() {
        insert("VIDEO", id("a"), "MODERATION", true);
        redis.opsForHash().put(cacheKey("video", id("a")), "MODERATION", "revoked");

        rebuilder.periodicRebuild();

        assertThat(redis.hasKey(cacheKey("video", id("a")))).isTrue();
    }

    private static String id(String name) {
        return RUN + "-" + name;
    }

    private static String cacheKey(String type, String id) {
        return "revocation:" + type + ":" + id;
    }

    private java.util.Set<Object> fields(String type, String id) {
        return redis.opsForHash().keys(cacheKey(type, id));
    }

    private void insert(String type, String id, String source, boolean active) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                """
                INSERT INTO platform.revocation (
                    subject_type, subject_id, source_type, source_version, active,
                    reason, blocking_version, created_at, updated_at, cleared_at
                ) VALUES (?, ?, ?, 1, ?, 'test', 1, ?, ?, ?)
                """,
                type, id, source, active, now, now, active ? null : now);
    }
}
