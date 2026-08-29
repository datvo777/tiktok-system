package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.events.Topics;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Milestone 3 acceptance: moderation, publication, and the 500 ms revocation
 * timing requirement (brief section 20, "Milestone 3 — Moderation and
 * publication"). The worker is played by hand exactly as in
 * {@link UploadTranscodeFlowIT}; see that class for why.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ModerationPublicationFlowIT {

    private static final String BUCKET = "short-video";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine")
            .withDatabaseName("short_video")
            .withUsername("short_video_app")
            .withPassword("short_video_app");

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"));

    @Container
    @SuppressWarnings("resource")
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shortvideo.outbox.enabled", () -> "true");
        registry.add("shortvideo.outbox.poll-interval", () -> "200ms");
        registry.add("shortvideo.minio.endpoint", minio::getS3URL);
        registry.add("shortvideo.minio.access-key", () -> "minioadmin");
        registry.add("shortvideo.minio.secret-key", () -> "minioadmin");
        registry.add("shortvideo.minio.bucket", () -> BUCKET);
    }

    @BeforeAll
    static void createBucket() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials("minioadmin", "minioadmin")
                .build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int port;

    private String creatorEmail;
    private String adminEmail;

    @BeforeEach
    void setUp() {
        creatorEmail = "creator-" + UUID.randomUUID() + "@example.com";
        adminEmail = "admin-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void hiddenUntilApprovedThenPubliclyPlayableThenRejectedWithin500ms() throws Exception {
        HttpHeaders creatorAuth = registerAndLogin(creatorEmail);
        HttpHeaders adminAuth = registerAdminAndLogin(adminEmail);

        // 1. Upload and reach READY (worker played by hand, as in UploadTranscodeFlowIT).
        ResponseEntity<Map> created = post("/api/v1/uploads", null, creatorAuth);
        String videoId = (String) created.getBody().get("videoId");
        String uploadId = (String) created.getBody().get("uploadId");
        putToPresignedUrl((String) created.getBody().get("uploadUrl"), "x".getBytes(StandardCharsets.UTF_8));
        post("/api/v1/uploads/" + uploadId + "/complete", null, creatorAuth);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        get("/api/v1/videos/" + videoId, creatorAuth).getBody().get("processingState"))
                .isEqualTo("TRANSCODING"));

        String finalPrefix = "processed/" + videoId + "/1/";
        stageObject(finalPrefix + "master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1x1\n720p/index.m3u8\n");
        stageObject(finalPrefix + "720p/index.m3u8", "#EXTM3U\n#EXT-X-ENDLIST\n");
        publishMediaResult(videoId, 1, "COMPLETED", finalPrefix);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        get("/api/v1/videos/" + videoId, creatorAuth).getBody().get("processingState"))
                .isEqualTo("READY"));

        // 2. A video with no moderation decision yet is denied publicly, even
        // though processing is done (brief section 7.1, Rule 9).
        assertThat(post("/api/v1/videos/" + videoId + "/public-playback-session", null, creatorAuth).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 3. Owner requests publication before moderation has decided: PUBLISH_PENDING, not PUBLISHED.
        ResponseEntity<Map> published = post("/api/v1/videos/" + videoId + "/publish", null, creatorAuth);
        assertThat(published.getBody().get("state")).isEqualTo("PUBLISH_PENDING");

        // 4. Admin approves; the coordinator reevaluates and the video becomes PUBLISHED.
        assertThat(post("/internal/v1/videos/" + videoId + "/approve", null, adminAuth).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Boolean eligible = jdbc.queryForObject(
                    "SELECT is_video_eligible FROM eligibility.video_eligibility WHERE video_id = ?",
                    Boolean.class,
                    videoId);
            assertThat(eligible).isTrue();
        });

        // 5. Approved processed video is publicly playable only now that the full invariant passes.
        ResponseEntity<Map> publicSession =
                post("/api/v1/videos/" + videoId + "/public-playback-session", null, creatorAuth);
        assertThat(publicSession.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = publicSession.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String playbackCookie = setCookie.substring(0, setCookie.indexOf(';'));
        String token = creatorAuth.getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length());

        HttpHeaders mediaHeaders = new HttpHeaders();
        mediaHeaders.add(HttpHeaders.COOKIE, "sv_session=" + token + "; " + playbackCookie);
        ResponseEntity<String> asset = getString("/media/videos/" + videoId + "/1/master.m3u8", mediaHeaders);
        assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6. Reject: durable revocation, immediate Redis update, and the gateway
        // must deny the very next request within 500 ms of the commit (brief
        // section 20's Milestone 3 timing target — a bounded functional check,
        // not the percentile SLA Milestone 8 adds later).
        Instant beforeReject = Instant.now();
        ResponseEntity<Void> reject = rest.exchange(
                url("/internal/v1/videos/" + videoId + "/reject"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "policy violation"), adminAuth),
                Void.class);
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterReject = getString("/media/videos/" + videoId + "/1/master.m3u8", mediaHeaders);
        Duration elapsed = Duration.between(beforeReject, Instant.now());

        assertThat(afterReject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(afterReject.getBody()).doesNotContain("#EXTM3U");
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));

        // 7. Confirmed restriction is visible durably, not just in cache.
        Boolean revoked = jdbc.queryForObject(
                "SELECT active FROM platform.revocation WHERE subject_type = 'VIDEO' AND subject_id = ? AND source_type = 'MODERATION'",
                Boolean.class,
                videoId);
        assertThat(revoked).isTrue();

        // 8. Reinstatement (re-approve) clears only the moderation revocation and
        // the coordinator reevaluates; the video is playable again.
        assertThat(post("/internal/v1/videos/" + videoId + "/approve", null, adminAuth).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ResponseEntity<String> retry = getString("/media/videos/" + videoId + "/1/master.m3u8", mediaHeaders);
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        });
    }

    private void stageObject(String objectKey, String content) throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials("minioadmin", "minioadmin")
                .build();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .build());
    }

    private void publishMediaResult(String videoId, int processingVersion, String outcome, String finalPrefix)
            throws Exception {
        MediaEvents.Assets assets = finalPrefix == null
                ? null
                : new MediaEvents.Assets(
                        finalPrefix + "master.m3u8", List.of(finalPrefix + "720p/index.m3u8"), 1, 3.0);
        var payload = new MediaEvents.MediaResultCommand(
                videoId + ":" + processingVersion, videoId, processingVersion, outcome, assets, null);
        var envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.MEDIA_RESULT_REPORTED,
                1,
                AggregateTypes.VIDEO,
                videoId,
                null,
                Instant.now(),
                "media-worker",
                "media-worker",
                null,
                null,
                payload);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.MEDIA_RESULTS, videoId, objectMapper.writeValueAsString(envelope)))
                    .get();
        }
    }

    private void putToPresignedUrl(String uploadUrl, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(body.length);
        rest.getRestTemplate().exchange(uploadUrl, HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
    }

    private HttpHeaders registerAndLogin(String email) {
        register(email);
        String token = (String) login(email).getBody().get("token");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** No admin-registration endpoint exists; elevate directly in the database, as an operator would locally. */
    private HttpHeaders registerAdminAndLogin(String email) {
        register(email);
        jdbc.update("UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = ?", email);
        String token = (String) login(email).getBody().get("token");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> register(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", email, "password", "correct-horse-battery", "displayName", "Test User");
        return rest.exchange(url("/api/v1/accounts"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> login(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", email, "password", "correct-horse-battery");
        return rest.exchange(url("/api/v1/auth/login"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<String> getString(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
