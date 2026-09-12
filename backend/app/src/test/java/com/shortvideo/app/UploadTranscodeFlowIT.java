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
import java.util.HashMap;
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
 * Milestone 2 acceptance, end to end against real PostgreSQL, Kafka, and MinIO.
 *
 * <p>The media worker is not run here — it is a separate process with its own
 * ffmpeg dependency, exercised live rather than in this suite. This test plays
 * the worker's part by hand: it stages the same HLS objects a real transcode
 * would have promoted, then publishes the same {@code media.results.v1} message
 * the worker would have sent. That isolates the Video module's own consumer
 * logic (dedup, stale-version rejection, the READY transition) from FFmpeg.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class UploadTranscodeFlowIT {

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
        // This IT exercises the real relay and consumers, unlike AccountFlowIT.
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

    private String email;

    @BeforeEach
    void setUp() {
        email = "creator-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void uploadTranscodePreviewFlowEndToEnd() throws Exception {
        register(email, "correct-horse-battery");
        Map<?, ?> login = login(email, "correct-horse-battery").getBody();
        String token = (String) login.get("token");
        HttpHeaders auth = bearer(token);

        // 1. Create the upload session; the video draft is created in the same
        // transaction (brief section 7.1).
        ResponseEntity<Map> created = post("/api/v1/uploads", Map.of("title", "Test video"), auth);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String uploadId = (String) created.getBody().get("uploadId");
        String videoId = (String) created.getBody().get("videoId");
        String uploadUrl = (String) created.getBody().get("uploadUrl");
        assertThat(uploadUrl).doesNotContain("processed/"); // never a processed/ read URL (Rule 18)

        // 2. The browser PUTs directly to MinIO.
        byte[] payload = "not a real mp4, only size matters for this test".getBytes(StandardCharsets.UTF_8);
        putToPresignedUrl(uploadUrl, payload);

        // 3. Complete the upload; verify it is owner-checked and idempotent.
        ResponseEntity<Map> completed = post("/api/v1/uploads/" + uploadId + "/complete", null, auth);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().get("status")).isEqualTo("COMPLETED");
        ResponseEntity<Map> completedAgain = post("/api/v1/uploads/" + uploadId + "/complete", null, auth);
        assertThat(completedAgain.getBody()).isEqualTo(completed.getBody());

        // 4. The Video module consumes video.upload.completed asynchronously and
        // dispatches a transcode command; wait for TRANSCODING.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<?, ?> status = get("/api/v1/videos/" + videoId, auth).getBody();
            assertThat(status.get("processingState")).isEqualTo("TRANSCODING");
            assertThat(status.get("processingVersion")).isEqualTo(1);
        });

        Integer dispatched = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                videoId,
                EventTypes.MEDIA_JOB_DISPATCHED);
        assertThat(dispatched).isEqualTo(1);

        // 5. Play the worker's part: stage the HLS assets it would have promoted,
        // then publish the same media.results.v1 message it would have sent.
        String finalPrefix = "processed/" + videoId + "/1/";
        stageObject(finalPrefix + "master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1x1\n720p/index.m3u8\n");
        stageObject(finalPrefix + "720p/index.m3u8", "#EXTM3U\n#EXT-X-ENDLIST\n");
        publishMediaResult(videoId, 1, "COMPLETED", finalPrefix, null);

        // 6. The video reaches READY + DURABLE from the worker's result alone.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<?, ?> status = get("/api/v1/videos/" + videoId, auth).getBody();
            assertThat(status.get("processingState")).isEqualTo("READY");
            assertThat(status.get("durabilityState")).isEqualTo("DURABLE");
        });

        // 7. A redelivered result (same jobId, fresh eventId) produces no second
        // transition — still exactly one video.processing.ready event.
        publishMediaResult(videoId, 1, "COMPLETED", finalPrefix, null);
        Thread.sleep(1000);
        Integer readyEvents = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                videoId,
                EventTypes.VIDEO_PROCESSING_READY);
        assertThat(readyEvents).isEqualTo(1);

        // 8. Owner preview session, then a real authorized, streamed request.
        ResponseEntity<Map> session =
                post("/api/v1/videos/" + videoId + "/preview-playback-session", null, auth);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = session.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("sv_playback=");
        assertThat(setCookie).contains("Path=/media/videos/" + videoId + "/1/");
        String playbackCookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpHeaders mediaHeaders = new HttpHeaders();
        mediaHeaders.add(HttpHeaders.COOKIE, "sv_session=" + token + "; " + playbackCookie);
        ResponseEntity<String> asset = getString("/media/videos/" + videoId + "/1/master.m3u8", mediaHeaders);
        assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asset.getBody()).contains("720p/index.m3u8");
        assertThat(asset.getHeaders().getCacheControl()).contains("no-store");

        // 9. The cookie is scoped to version 1; requesting version 2 is rejected.
        ResponseEntity<String> wrongVersion = getString("/media/videos/" + videoId + "/2/master.m3u8", mediaHeaders);
        assertThat(wrongVersion.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTerminalTranscodeFailureMarksTheVideoFailed() throws Exception {
        register(email, "correct-horse-battery");
        HttpHeaders auth = bearer((String) login(email, "correct-horse-battery").getBody().get("token"));

        ResponseEntity<Map> created = post("/api/v1/uploads", Map.of("title", "Test video"), auth);
        String uploadId = (String) created.getBody().get("uploadId");
        String videoId = (String) created.getBody().get("videoId");
        String uploadUrl = (String) created.getBody().get("uploadUrl");
        putToPresignedUrl(uploadUrl, "x".getBytes(StandardCharsets.UTF_8));
        post("/api/v1/uploads/" + uploadId + "/complete", null, auth);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        get("/api/v1/videos/" + videoId, auth).getBody().get("processingState"))
                .isEqualTo("TRANSCODING"));

        publishMediaResult(videoId, 1, "FAILED", null, "TERMINAL");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<?, ?> status = get("/api/v1/videos/" + videoId, auth).getBody();
            assertThat(status.get("processingState")).isEqualTo("FAILED");
            assertThat(status.get("failureClass")).isEqualTo("TERMINAL");
        });

        ResponseEntity<Map> session = post("/api/v1/videos/" + videoId + "/preview-playback-session", null, auth);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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

    /** Simulates the worker: publishes the same envelope shape it would send, with no outbox (Rule 16). */
    private void publishMediaResult(
            String videoId, int processingVersion, String outcome, String finalPrefix, String failureClass)
            throws Exception {
        MediaEvents.Assets assets = finalPrefix == null
                ? null
                : new MediaEvents.Assets(
                        finalPrefix + "master.m3u8", List.of(finalPrefix + "720p/index.m3u8"), 1, 3.0);
        var payload = new MediaEvents.MediaResultCommand(
                videoId + ":" + processingVersion, videoId, processingVersion, outcome, assets, failureClass);
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
            producer.send(new ProducerRecord<>(
                            Topics.MEDIA_RESULTS, videoId, objectMapper.writeValueAsString(envelope)))
                    .get();
        }
    }

    private void putToPresignedUrl(String url, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(body.length);
        rest.getRestTemplate()
                .exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
    }

    private ResponseEntity<Map> register(String address, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", address, "password", password, "displayName", "Test Creator");
        return rest.exchange(url("/api/v1/accounts"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> login(String address, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", address, "password", password);
        return rest.exchange(url("/api/v1/auth/login"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
