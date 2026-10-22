package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Writes that recover from a unique-index conflict, against real PostgreSQL.
 *
 * <p>PostgreSQL aborts the whole transaction when a statement fails (SQLState 25P02),
 * so a service that catches the violation and runs another query in the same
 * transaction gets that query refused. Mocks and H2 do not reproduce this, which is
 * why these run over HTTP against the real database: a repeated report and two first
 * saves racing must both succeed, as their comments promise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdempotentWritesIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @LocalServerPort
    int port;

    private HttpHeaders auth;
    private String accountId;

    @BeforeEach
    void setUp() {
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
        String email = "viewer-" + UUID.randomUUID() + "@example.com";
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> registered = rest.exchange(
                        url("/api/v1/accounts"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("email", email, "password", "correct-horse-battery", "displayName", "Viewer"),
                                json),
                        Map.class)
                .getBody();
        accountId = (String) registered.get("accountId");
        Map<?, ?> login = rest.exchange(
                        url("/api/v1/auth/login"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), json),
                        Map.class)
                .getBody();
        auth = new HttpHeaders();
        auth.setBearerAuth((String) login.get("token"));
        auth.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void reportingTheSameVideoTwiceReturnsTheFirstReportInsteadOfFailing() {
        String videoId = eligibleVideo();
        Map<String, String> body = Map.of("subjectType", "VIDEO", "reason", "SPAM_OR_SCAM");

        ResponseEntity<Map> first = post("/api/v1/reports/" + videoId, body);
        ResponseEntity<Map> second = post("/api/v1/reports/" + videoId, body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("reportId")).isEqualTo(first.getBody().get("reportId"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM report.report WHERE subject_id = ?::uuid AND reporter_id = ?::uuid",
                        Integer.class,
                        videoId,
                        accountId))
                .isEqualTo(1);
    }

    @Test
    void concurrentFirstSavesAllSucceedAndShareOneDefaultCollection() throws Exception {
        int callers = 6;
        List<String> videos = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            videos.add(eligibleVideo());
        }
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (String videoId : videos) {
                results.add(pool.submit(() -> {
                    start.await();
                    return (HttpStatus) post("/api/v1/favorites/videos/" + videoId, null).getStatusCode();
                }));
            }
            start.countDown();
            for (Future<HttpStatus> result : results) {
                assertThat(result.get()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM social.favorite_collection WHERE account_id = ?::uuid",
                        Integer.class,
                        accountId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM social.favorite_item i JOIN social.favorite_collection c"
                                + " ON c.collection_id = i.collection_id WHERE c.account_id = ?::uuid",
                        Integer.class,
                        accountId))
                .isEqualTo(callers);
    }

    /** A video the eligibility projection says is servable, created by someone else. */
    private String eligibleVideo() {
        String videoId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO eligibility.video_eligibility (
                    video_id, creator_id, processing_state, durability_state, asset_lifecycle_state,
                    legal_serving_state, moderation_state, publication_state, publication_intent_requested,
                    is_video_eligible, processing_version_source, moderation_version_source,
                    publication_version_source, updated_at)
                VALUES (?, ?, 'READY', 'DURABLE', 'ACTIVE', 'CLEAR', 'APPROVED', 'PUBLISHED', true,
                    true, 1, 1, 1, now())
                """,
                videoId,
                UUID.randomUUID().toString());
        return videoId;
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, auth), Map.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
