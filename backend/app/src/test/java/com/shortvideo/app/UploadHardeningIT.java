package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Upload create/complete under concurrency and abuse, against real PostgreSQL and MinIO:
 * the quota holds under parallel creates, a retried create is idempotent, a verified
 * source cannot be swapped by re-posting to the still-valid presigned policy, and a
 * non-ACTIVE account cannot open an upload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class UploadHardeningIT {

    private static final String BUCKET = "short-video";
    private static final int QUOTA = 5;
    private static final int RATE_LIMIT = 20;
    /** Small enough that the open-session count, not the reserved-bytes cap, is what binds. */
    private static final long SMALL_FILE = 1024 * 1024;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine")
            .withDatabaseName("short_video")
            .withUsername("short_video_app")
            .withPassword("short_video_app");

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
        // Nothing here publishes or consumes (the relay is disabled in the test profile),
        // so no broker is needed; listeners just retry their connection in the background.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:29092");
        registry.add("shortvideo.minio.endpoint", minio::getS3URL);
        registry.add("shortvideo.minio.access-key", () -> "minioadmin");
        registry.add("shortvideo.minio.secret-key", () -> "minioadmin");
        registry.add("shortvideo.minio.bucket", () -> BUCKET);
        registry.add("shortvideo.upload.create-rate-limit.max-per-window", () -> String.valueOf(RATE_LIMIT));
    }

    @BeforeAll
    static void createBucket() throws Exception {
        minioClient().makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Autowired
    StringRedisTemplate redis;

    @LocalServerPort
    int port;

    private HttpHeaders auth;
    private String accountId;

    @BeforeEach
    void setUp() {
        // The per-IP login/registration counters are shared by every test, since they all
        // come from loopback, and would otherwise throttle later tests in the run.
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> registered = rest.exchange(
                        url("/api/v1/accounts"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("email", email, "password", "correct-horse-battery", "displayName", "Creator"),
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
    void parallelCreatesCannotExceedTheOpenSessionQuota() throws Exception {
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(create(null, SMALL_FILE).getStatusCode().value());
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<HttpStatus> f : results) {
            if (f.get() == HttpStatus.CREATED) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(QUOTA);
        assertThat(openSessions()).isEqualTo(QUOTA);
    }

    @Test
    void theCleanupJobRunsUnderASchedulerLock() {
        Object job = context.getBean("uploadCleanupJob");
        assertThat(AopUtils.isAopProxy(job)).as("lock advice is applied to the bean").isTrue();
        var sweep = ReflectionUtils.findMethod(job.getClass(), "sweep");
        ReflectionUtils.makeAccessible(sweep);

        ReflectionUtils.invokeMethod(sweep, job);

        // lockAtLeastFor keeps the row locked after a fast sweep, so another instance's
        // sweep right behind it is skipped.
        Boolean heldIntoTheFuture = jdbc.queryForObject(
                "SELECT lock_until > (now() AT TIME ZONE 'UTC') FROM upload.shedlock WHERE name = 'uploadCleanup'", Boolean.class);
        assertThat(heldIntoTheFuture).isTrue();
    }

    @Test
    void theDeclaredSizeBecomesTheCapTheSessionIsIssuedWith() {
        ResponseEntity<Map> created = create(null, SMALL_FILE);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) created.getBody().get("maxBytes")).longValue()).isEqualTo(SMALL_FILE);
    }

    @Test
    void sessionsThatReserveTheDefaultMaximumAreHeldToTheOpenBytesCap() {
        // Two default-size (500 MiB) sessions fit in the 1 GiB cap; a third does not.
        assertThat(create(null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> third = create(null);

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Declaring what is actually being uploaded still fits.
        assertThat(create(null, SMALL_FILE).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aDeclaredSizeOutsideTheAllowedRangeIsRejectedBeforeAnythingIsCreated() {
        assertThat(create(null, 0L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(null, 500L * 1024 * 1024 + 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(openSessions()).isZero();
    }

    @Test
    void aRetriedCreateWithTheSameKeyReturnsTheSameSessionAndConsumesNoMoreQuota() {
        String key = "create-" + UUID.randomUUID();

        ResponseEntity<Map> first = create(key);
        ResponseEntity<Map> retry = create(key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().get("uploadId")).isEqualTo(first.getBody().get("uploadId"));
        assertThat(retry.getBody().get("videoId")).isEqualTo(first.getBody().get("videoId"));
        assertThat(openSessions()).isEqualTo(1);
        assertThat(videoCount()).isEqualTo(1);
        // A different key is a different request.
        assertThat(create("create-" + UUID.randomUUID()).getBody().get("uploadId"))
                .isNotEqualTo(first.getBody().get("uploadId"));
    }

    @Test
    void parallelCreatesWithOneKeyProduceOneSession() throws Exception {
        String key = "create-" + UUID.randomUUID();
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> ids = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            ids.add(pool.submit(() -> {
                start.await();
                return create(key).getBody().get("uploadId");
            }));
        }
        start.countDown();
        List<Object> distinct = new ArrayList<>();
        for (Future<Object> f : ids) {
            Object id = f.get();
            if (!distinct.contains(id)) {
                distinct.add(id);
            }
        }
        pool.shutdown();

        assertThat(distinct).hasSize(1);
        assertThat(openSessions()).isEqualTo(1);
    }

    @Test
    void concurrentCompletionsOfOneSessionAllSucceedAndEmitOneEvent() throws Exception {
        Map<?, ?> created = create(null).getBody();
        String uploadId = (String) created.get("uploadId");
        assertThat(postFile(created, "one upload, completed several times at once")).isBetween(200, 299);

        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return rest.exchange(
                        url("/api/v1/uploads/" + uploadId + "/complete"),
                        HttpMethod.POST,
                        new HttpEntity<>(auth),
                        Map.class);
            }));
        }
        start.countDown();
        for (Future<ResponseEntity<Map>> result : results) {
            ResponseEntity<Map> response = result.get();
            // Not a 500 from losing a version race: the loser is told the upload is complete.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("status")).isEqualTo("COMPLETED");
        }
        pool.shutdown();

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = 'video.upload.completed'",
                Integer.class,
                uploadId);
        assertThat(events).isEqualTo(1);
    }

    @Test
    void aCompletionWaitingOnAHeldSessionLockGivesUpWithARetryableErrorInsteadOfHanging() throws Exception {
        Map<?, ?> created = create(null).getBody();
        String uploadId = (String) created.get("uploadId");
        assertThat(postFile(created, "uploaded, then something holds the row")).isBetween(200, 299);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> held = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT 1 FROM upload.upload_session WHERE upload_id = ?::uuid FOR UPDATE", uploadId);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        ResponseEntity<Map> completed = rest.exchange(
                url("/api/v1/uploads/" + uploadId + "/complete"), HttpMethod.POST, new HttpEntity<>(auth), Map.class);
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;
        release.countDown();
        held.get();
        holder.shutdown();

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(waitedMillis).isBetween(4_000L, 15_000L);
        // Nothing was half-done: once the lock is free, completing works.
        assertThat(rest.exchange(
                                url("/api/v1/uploads/" + uploadId + "/complete"),
                                HttpMethod.POST,
                                new HttpEntity<>(auth),
                                Map.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void completingBeforeAnythingWasUploadedReportsAMachineReadableReason() {
        Map<?, ?> created = create(null).getBody();

        ResponseEntity<Map> completed = rest.exchange(
                url("/api/v1/uploads/" + created.get("uploadId") + "/complete"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                Map.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(completed.getBody().get("code")).isEqualTo("UPLOAD_OBJECT_MISSING");
    }

    @Test
    void anAccountSuspendedWhileUploadingCannotCompleteIt() throws Exception {
        Map<?, ?> created = create(null).getBody();
        String uploadId = (String) created.get("uploadId");
        assertThat(postFile(created, "uploaded before the account was restricted")).isBetween(200, 299);
        jdbc.update("UPDATE account.account SET state = 'RESTRICTED' WHERE account_id = ?::uuid", accountId);

        ResponseEntity<Map> completed = rest.exchange(
                url("/api/v1/uploads/" + uploadId + "/complete"), HttpMethod.POST, new HttpEntity<>(auth), Map.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = 'video.upload.completed'",
                Integer.class,
                uploadId);
        assertThat(events).isZero();
    }

    @Test
    void overwritingTheUploadAfterCompleteCannotChangeTheVerifiedSource() throws Exception {
        Map<?, ?> created = create(null).getBody();
        String uploadId = (String) created.get("uploadId");
        String videoId = (String) created.get("videoId");

        assertThat(postFile(created, "the bytes that were verified")).isBetween(200, 299);
        ResponseEntity<Map> completed = rest.exchange(
                url("/api/v1/uploads/" + uploadId + "/complete"), HttpMethod.POST, new HttpEntity<>(auth), Map.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The policy is still valid until it expires; a client can post again.
        assertThat(postFile(created, "DIFFERENT bytes swapped in afterwards")).isBetween(200, 299);

        assertThat(readObject("sources/" + videoId + "/original")).isEqualTo("the bytes that were verified");
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = 'video.upload.completed'",
                String.class,
                uploadId);
        assertThat(payload).contains("sources/" + videoId + "/original").doesNotContain("uploads/");
    }

    @Test
    void anUploadThatFinishesAfterTheDeadlineCanStillBeCompletedWithinTheGrace() throws Exception {
        Map<?, ?> created = create(null).getBody();
        String uploadId = (String) created.get("uploadId");
        assertThat(postFile(created, "a slow upload that finished late")).isBetween(200, 299);
        // The policy deadline passed while the body was still arriving.
        jdbc.update("UPDATE upload.upload_session SET expires_at = now() - interval '3 minutes' WHERE upload_id = ?::uuid", uploadId);

        ResponseEntity<Map> completed = rest.exchange(
                url("/api/v1/uploads/" + uploadId + "/complete"), HttpMethod.POST, new HttpEntity<>(auth), Map.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void completionIsRefusedOnceTheGraceWindowIsAlsoOver() throws Exception {
        Map<?, ?> created = create(null).getBody();
        String uploadId = (String) created.get("uploadId");
        assertThat(postFile(created, "far too late")).isBetween(200, 299);
        jdbc.update("UPDATE upload.upload_session SET expires_at = now() - interval '2 hours' WHERE upload_id = ?::uuid", uploadId);

        ResponseEntity<Map> completed = rest.exchange(
                url("/api/v1/uploads/" + uploadId + "/complete"), HttpMethod.POST, new HttpEntity<>(auth), Map.class);

        assertThat(completed.getStatusCode().is4xxClientError()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM upload.upload_session WHERE upload_id = ?::uuid", String.class, uploadId))
                .isEqualTo("PENDING");
    }

    @Test
    void anotherAccountCompletingSomeonesUploadSeesNotFoundNotForbidden() {
        String uploadId = (String) create(null).getBody().get("uploadId");
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        rest.exchange(
                url("/api/v1/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", otherEmail, "password", "correct-horse-battery", "displayName", "Other"), json),
                Map.class);
        Map<?, ?> login = rest.exchange(
                        url("/api/v1/auth/login"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", otherEmail, "password", "correct-horse-battery"), json),
                        Map.class)
                .getBody();
        HttpHeaders other = new HttpHeaders();
        other.setBearerAuth((String) login.get("token"));

        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/uploads/" + uploadId + "/complete"), HttpMethod.POST, new HttpEntity<>(other), Map.class);

        // Same answer as GET and as an unknown id: ownership of an upload id is not disclosed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAccountThatIsNotActiveCannotOpenAnUpload() {
        jdbc.update("UPDATE account.account SET state = 'RESTRICTED' WHERE account_id = ?::uuid", accountId);

        assertThat(create(null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(openSessions()).isZero();
    }

    @Test
    void startingTooManyUploadsInAWindowIsThrottledEvenWhenQuotaAllowsThem() {
        // Replays of one key consume no quota, so only the rate limit can stop these.
        String key = "create-" + UUID.randomUUID();
        for (int i = 0; i < RATE_LIMIT; i++) {
            assertThat(create(key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> throttled = create(key);

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void aTitleWithControlCharactersIsRejectedBeforeAnythingIsCreated() {
        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/uploads"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "bad\u0000title"), auth),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(openSessions()).isZero();
    }

    private ResponseEntity<Map> create(String idempotencyKey) {
        return create(idempotencyKey, null);
    }

    private ResponseEntity<Map> create(String idempotencyKey, Long sizeBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", "Video");
        if (sizeBytes != null) {
            body.put("sizeBytes", sizeBytes);
        }
        return rest.exchange(url("/api/v1/uploads"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    /** Posts the presigned form the way a browser does: fields first, file part last. */
    @SuppressWarnings("unchecked")
    private int postFile(Map<?, ?> created, String content) throws Exception {
        String boundary = "----it" + UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> field : ((Map<String, String>) created.get("formFields")).entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                            + "\"\r\n\r\n" + field.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"video.mp4\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) java.net.URI.create((String) created.get("uploadUrl")).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (var out = conn.getOutputStream()) {
            out.write(body.toByteArray());
        }
        return conn.getResponseCode();
    }

    private String readObject(String key) throws Exception {
        try (var in = minioClient().getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int openSessions() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM upload.upload_session WHERE account_id = ?::uuid AND status = 'PENDING'",
                Integer.class,
                accountId);
    }

    private int videoCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM upload.upload_session WHERE account_id = ?::uuid", Integer.class, accountId);
    }

    private static MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials("minioadmin", "minioadmin")
                .build();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
