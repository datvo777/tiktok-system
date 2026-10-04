package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ReflectionUtils;

/**
 * Terminally failed videos get their source scheduled for purge once the retention window has
 * passed, exactly once, and never while the failure is recent or not terminal. The selection
 * query is JPQL with a NOT EXISTS, so it is only meaningfully checked against PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FailedSourceCleanupIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ApplicationContext context;
    @LocalServerPort int port;

    @Test
    void onlyOldTerminalFailuresAreScheduledAndOnlyOnce() {
        String oldTerminal = draftVideo();
        String recentTerminal = draftVideo();
        String oldTransient = draftVideo();
        fail(oldTerminal, "TERMINAL", "8 days");
        fail(recentTerminal, "TERMINAL", "1 day");
        fail(oldTransient, "TRANSIENT", "8 days");

        sweep();
        sweep();

        assertThat(sourceRows(oldTerminal)).containsExactly("sources/" + oldTerminal + "/");
        assertThat(sourceRows(recentTerminal)).isEmpty();
        assertThat(sourceRows(oldTransient)).isEmpty();
    }

    private void sweep() {
        Object job = context.getBean("failedSourceCleanupJob");
        var method = ReflectionUtils.findMethod(job.getClass(), "sweep");
        ReflectionUtils.makeAccessible(method);
        ReflectionUtils.invokeMethod(method, job);
    }

    private List<String> sourceRows(String videoId) {
        return jdbc.queryForList(
                "SELECT purge_prefix FROM video.superseded_asset WHERE video_id = ?::uuid AND purge_prefix IS NOT NULL",
                String.class,
                videoId);
    }

    private void fail(String videoId, String failureClass, String age) {
        jdbc.update(
                "UPDATE video.video SET processing_state = 'FAILED', failure_class = ?,"
                        + " updated_at = now() - ?::interval WHERE video_id = ?::uuid",
                failureClass,
                age,
                videoId);
    }

    /** A video draft, made the way a client makes one: by opening an upload session. */
    private String draftVideo() {
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
        String email = "viewer-" + UUID.randomUUID() + "@example.com";
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(
                url("/api/v1/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery", "displayName", "V"), json),
                Map.class);
        Map<?, ?> login = rest.exchange(
                        url("/api/v1/auth/login"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), json),
                        Map.class)
                .getBody();
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth((String) login.get("token"));
        auth.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> created = rest.exchange(
                        url("/api/v1/uploads"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("title", "Video", "sizeBytes", 1024), auth),
                        Map.class)
                .getBody();
        return (String) created.get("videoId");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
