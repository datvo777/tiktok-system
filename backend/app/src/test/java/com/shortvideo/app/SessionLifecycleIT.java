package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.security.JwtService;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Covers the three session-lifetime behaviours added after the audit: login rate
 * limiting, session renewal, and the cap on concurrent upload sessions.
 *
 * <p>Needs Redis as well as PostgreSQL — the rate limiter and the logout deny-list
 * both live there, and mocking them would test the mock rather than the behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionLifecycleIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        // Low enough to trip deliberately within a test, without a sleep.
        registry.add("shortvideo.login-rate-limit.enabled", () -> "true");
        registry.add("shortvideo.login-rate-limit.max-attempts-per-ip", () -> "6");
        registry.add("shortvideo.login-rate-limit.max-failures-per-account", () -> "3");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtService jwtService;

    @Autowired
    StringRedisTemplate redis;

    @LocalServerPort
    int port;

    private String email;

    @BeforeEach
    void setUp() {
        email = "session-" + UUID.randomUUID() + "@example.com";
        // The IP counter is shared across tests in this class, since they all
        // originate from the same loopback address.
        redis.keys("login:*").forEach(redis::delete);
    }

    // ------------------------------------------------------------ rate limiting

    /**
     * Verifying a password is ~100ms of deliberate BCrypt cost on an endpoint that
     * requires no credentials to reach, so an unbounded attempt rate is both a
     * guessing oracle and a cheap way to burn CPU.
     */
    @Test
    void repeatedFailuresAgainstOneAccountAreThrottled() {
        register(email);

        // 3 failures allowed, the 4th is refused.
        for (int i = 0; i < 3; i++) {
            assertThat(login(email, "wrong-password-here").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Map> throttled = login(email, "wrong-password-here");

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    /**
     * The throttle must not become a way to lock someone out of their own account:
     * a correct password clears the counter.
     */
    @Test
    void aSuccessfulLoginClearsTheFailureCount() {
        register(email);

        assertThat(login(email, "wrong-password-here").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, "wrong-password-here").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, "correct-horse-battery").getStatusCode()).isEqualTo(HttpStatus.OK);

        // Would be the 4th failure and therefore throttled, if success had not reset it.
        assertThat(login(email, "wrong-password-here").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------- renewal

    /**
     * A 30-minute TTL with no renewal signs an active user out mid-task. Renewal
     * replaces the session rather than adding to it: the old token stops working,
     * so a stolen token cannot be kept alive alongside the legitimate one.
     */
    @Test
    void refreshIssuesANewSessionAndRevokesTheOldToken() {
        String original = registerAndLogin();
        assertThat(getStatus("/api/v1/auth/me", original)).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> refreshed = exchange("/api/v1/auth/refresh", HttpMethod.POST, original, null);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String renewed = (String) refreshed.getBody().get("token");

        assertThat(renewed).isNotEqualTo(original);
        assertThat(getStatus("/api/v1/auth/me", renewed)).isEqualTo(HttpStatus.OK);
        assertThat(getStatus("/api/v1/auth/me", original)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRequiresAValidSession() {
        assertThat(exchange("/api/v1/auth/refresh", HttpMethod.POST, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A signed-out token must not be able to renew itself back into validity. */
    @Test
    void aLoggedOutTokenCannotRefresh() {
        String token = registerAndLogin();
        exchange("/api/v1/auth/logout", HttpMethod.POST, token, null);

        assertThat(exchange("/api/v1/auth/refresh", HttpMethod.POST, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------- upload cap

    /**
     * The presigned policy caps one upload's size; it says nothing about how many
     * an account may hold open. Each open session is a writable allowance against
     * the bucket and a video draft row, so the count needs its own bound.
     */
    @Test
    void anAccountCannotHoldUnlimitedOpenUploadSessions() {
        String token = registerAndLogin();

        for (int i = 0; i < 5; i++) {
            assertThat(exchange("/api/v1/uploads", HttpMethod.POST, token, Map.of("title", "Test video")).getStatusCode())
                    .as("session %d should be allowed", i + 1)
                    .isEqualTo(HttpStatus.CREATED);
        }
        assertThat(exchange("/api/v1/uploads", HttpMethod.POST, token, Map.of("title", "Test video")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------- helpers

    private void register(String address) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(
                url("/api/v1/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", address, "password", "correct-horse-battery", "displayName", "Session Test"),
                        headers),
                Map.class);
    }

    private ResponseEntity<Map> login(String address, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                url("/api/v1/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", address, "password", password), headers),
                Map.class);
    }

    private String registerAndLogin() {
        register(email);
        return (String) login(email, "correct-horse-battery").getBody().get("token");
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
    }

    private HttpStatus getStatus(String path, String token) {
        return (HttpStatus) exchange(path, HttpMethod.GET, token, null).getStatusCode();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
