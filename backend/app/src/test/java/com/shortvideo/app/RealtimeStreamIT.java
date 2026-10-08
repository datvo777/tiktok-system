package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shortvideo.notification.realtime.SseRegistry;
import com.shortvideo.shared.security.CredentialFreshnessCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The realtime stream against a real server: the session cookie authorises it, an account only
 * hears about its own changes, the limits hold, and an account that loses standing is cut off.
 *
 * <p>Events are pushed into {@link SseRegistry} directly. The Kafka leg (notification.created
 * reaching the registry) is covered by {@code RealtimeListenerTest}; nothing here needs a broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealtimeStreamIT {

    private static final int GLOBAL_LIMIT = 6;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.register-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.realtime.enabled", () -> "true");
        registry.add("shortvideo.realtime.max-connections", () -> String.valueOf(GLOBAL_LIMIT));
        // Long enough that nothing here is closed by the clock; the tests drive revalidation by hand.
        registry.add("shortvideo.realtime.heartbeat-interval", () -> "1h");
        registry.add("shortvideo.realtime.revalidate-interval", () -> "1h");
    }

    @Autowired TestRestTemplate rest;
    @Autowired SseRegistry registry;
    @Autowired CredentialFreshnessCache credentialFreshness;
    @Autowired StringRedisTemplate redis;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
    }

    /**
     * A server learns that a client has gone only when a write to it fails, which for an idle stream
     * is the next heartbeat. Drive that here so one test's leftovers do not eat the next one's limit.
     */
    @AfterEach
    void waitForTheServerToNoticeClosedClients() {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            registry.heartbeat();
            assertThat(registry.openConnections()).isZero();
        });
    }

    // ---------------------------------------------------------------- auth

    @Test
    void theStreamRequiresASession() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/v1/events/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- delivery and isolation

    @Test
    void anAccountHearsOnlyAboutItsOwnChanges() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        try (Stream sa = open(a); Stream sb = open(b)) {
            sa.awaitConnected();
            sb.awaitConnected();

            registry.publish(a.id, "PROCESSING_READY", "video-1", Instant.now());

            assertThat(sa.next(5)).isEqualTo("event:changed");
            assertThat(sa.next(5)).isEqualTo("data:{\"type\":\"PROCESSING_READY\",\"videoId\":\"video-1\"}");
            // B's stream saw nothing of it: the next thing it can be told is its own event.
            registry.publish(b.id, "PROCESSING_FAILED", null, Instant.now());
            assertThat(sb.next(5)).isEqualTo("event:changed");
            assertThat(sb.next(5)).isEqualTo("data:{\"type\":\"PROCESSING_FAILED\",\"videoId\":null}");
        }
    }

    @Test
    void severalStreamsOfOneAccountAllHearIt() throws Exception {
        Account a = newAccount();
        try (Stream first = open(a); Stream second = open(a)) {
            first.awaitConnected();
            second.awaitConnected();

            registry.publish(a.id, "PROCESSING_READY", "v", Instant.now());

            assertThat(first.next(5)).isEqualTo("event:changed");
            assertThat(second.next(5)).isEqualTo("event:changed");
        }
    }

    // ---------------------------------------------------------------- limits

    @Test
    void aFourthStreamFromOneAccountClosesTheOldest() throws Exception {
        Account a = newAccount();
        List<Stream> streams = new java.util.ArrayList<>();
        try {
            // One at a time: which of them the server calls "oldest" is the order it received them in.
            for (int i = 0; i < 4; i++) {
                Stream s = open(a);
                streams.add(s);
                s.awaitConnected();
            }

            assertThat(streams.get(0).awaitClosed()).as("the oldest stream was ended by the server").isTrue();
            assertThat(registry.openForAccount(a.id)).isEqualTo(3);
            registry.publish(a.id, "X", null, Instant.now());
            for (Stream survivor : streams.subList(1, 4)) {
                assertThat(survivor.next(5)).isEqualTo("event:changed");
            }
        } finally {
            streams.forEach(Stream::close);
        }
    }

    @Test
    void pastTheInstanceLimitTheAnswerIs503WithRetryAfter() throws Exception {
        Account a = newAccount();
        Account b = newAccount();
        Account c = newAccount();
        int before = registry.openConnections();
        List<Stream> held = new java.util.ArrayList<>();
        try {
            for (Account acc : List.of(a, b)) {
                for (int i = 0; i < 3 && before + held.size() < GLOBAL_LIMIT; i++) {
                    Stream s = open(acc);
                    s.awaitConnected();
                    held.add(s);
                }
            }
            assertThat(registry.openConnections()).isEqualTo(GLOBAL_LIMIT);

            HttpResponse<Void> refused = http.send(
                    request(c).build(), HttpResponse.BodyHandlers.discarding());

            assertThat(refused.statusCode()).isEqualTo(503);
            assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        } finally {
            held.forEach(Stream::close);
        }
    }

    // ---------------------------------------------------------------- entitlement

    @Test
    void anAccountThatChangedItsPasswordAfterOpeningIsCutOff() throws Exception {
        Account a = newAccount();
        try (Stream s = open(a)) {
            s.awaitConnected();

            // What a password change leaves in the cache the filter and this check both read.
            credentialFreshness.put(a.id, Instant.now().plusSeconds(5));
            registry.revalidate();

            assertThat(s.awaitClosed()).isTrue();
            assertThat(registry.openForAccount(a.id)).isZero();
        }
    }

    @Test
    void aHealthyAccountSurvivesRevalidation() throws Exception {
        Account a = newAccount();
        try (Stream s = open(a)) {
            s.awaitConnected();

            registry.revalidate();
            registry.publish(a.id, "STILL_HERE", null, Instant.now());

            assertThat(s.next(5)).isEqualTo("event:changed");
        }
    }

    // ---------------------------------------------------------------- helpers

    private record Account(String id, String cookie) {}

    private Account newAccount() {
        String email = "rt-" + UUID.randomUUID() + "@example.com";
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> created = rest.exchange(
                        uri("/api/v1/accounts").toString(),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery", "displayName", "RT"), json),
                        Map.class)
                .getBody();
        var login = rest.exchange(
                uri("/api/v1/auth/login").toString(),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), json),
                Map.class);
        String setCookie = login.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("sv_session="))
                .findFirst()
                .orElseThrow();
        return new Account((String) created.get("accountId"), setCookie.substring(0, setCookie.indexOf(';')));
    }

    private HttpRequest.Builder request(Account account) {
        return HttpRequest.newBuilder(uri("/api/v1/events/stream"))
                .header("Cookie", account.cookie())
                .header("Accept", "text/event-stream")
                .GET();
    }

    /** Its own client, so closing the stream really drops the connection instead of pooling it. */
    private Stream open(Account account) {
        HttpClient own = HttpClient.newHttpClient();
        return new Stream(own, own.sendAsync(request(account).build(), HttpResponse.BodyHandlers.ofLines()));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** A client of the stream: collects its non-empty lines until the server ends it. */
    private static final class Stream implements AutoCloseable {
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response;
        private final HttpClient client;

        Stream(HttpClient client, CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response) {
            this.client = client;
            this.response = response;
            response.thenAcceptAsync(r -> {
                try (var body = r.body()) {
                    body.forEach(line -> {
                        if (!line.isEmpty()) {
                            lines.add(line);
                        }
                    });
                    finished.complete(null);
                } catch (RuntimeException e) {
                    finished.complete(null);
                }
            }).exceptionally(e -> {
                finished.complete(null);
                return null;
            });
        }

        void awaitConnected() throws Exception {
            assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(next(5)).isEqualTo(":connected");
        }

        String next(int seconds) throws InterruptedException {
            String line = lines.poll(seconds, TimeUnit.SECONDS);
            assertThat(line).as("a line from the stream within %ds", seconds).isNotNull();
            return line;
        }

        boolean awaitClosed() {
            try {
                finished.get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void close() {
            response.cancel(true);
            client.shutdownNow();
        }
    }
}
