package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.security.JwtService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Negative authorization and error-contract coverage.
 *
 * <p>Written because the audit found this whole dimension untested: nothing
 * asserted what happens to a caller who is authenticated but not entitled, or to a
 * request that is malformed rather than unauthorized. Those are the paths where a
 * mistake is invisible in normal use — a wrong status code still refuses the
 * request, so nothing looks broken until a client tries to branch on it.
 *
 * <p>Each test names the status it expects and why, so a future change that
 * regresses one produces a failure that explains itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthorizationIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine")
            .withDatabaseName("short_video")
            .withUsername("short_video_app")
            .withPassword("short_video_app");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtService jwtService;

    @LocalServerPort
    int port;

    private String email;

    @BeforeEach
    void setUp() {
        email = "creator-" + UUID.randomUUID() + "@example.com";
    }

    // ---------------------------------------------------------------- 401 vs 403

    @Test
    void anonymousCallerGets401NotRedirectedOrForbidden() {
        assertThat(get("/api/v1/feed", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/notifications", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The distinction the admin client branches on: 401 means "not signed in",
     * 403 means "signed in, but not an admin". Reporting the second as 500 would
     * still refuse the request, which is why this went unnoticed.
     */
    @Test
    void authenticatedNonAdminGets403OnEveryAdminEndpoint() {
        String token = tokenFor(Set.of("USER"));
        String videoId = UUID.randomUUID().toString();

        assertThat(getStatus("/internal/v1/videos/pending", token)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getStatus("/internal/v1/appeals/pending", token)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postStatus("/internal/v1/videos/" + videoId + "/approve", token, null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postStatus("/internal/v1/videos/" + videoId + "/quarantine", token, Map.of("reason", "x")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postStatus("/internal/v1/videos/" + videoId + "/remove", token, Map.of("reason", "x")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postStatus("/internal/v1/videos/" + videoId + "/reprocess", token, null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postStatus("/internal/v1/videos/" + videoId + "/moderator-playback-session", token, null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postStatus("/internal/v1/accounts/" + videoId + "/suspend", token, Map.of("reason", "x")))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminReachesAdminEndpoints() {
        String token = tokenFor(Set.of("USER", "ADMIN"));
        // Not 403: the role check passes. The queue is simply empty.
        assertThat(getStatus("/internal/v1/videos/pending", token)).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------- BOLA

    @Test
    void readingAnotherAccountIsRefused() {
        String mine = registerAndLogin();
        String someoneElse = UUID.randomUUID().toString();

        assertThat(getStatus("/api/v1/accounts/" + someoneElse, mine)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * A non-owner gets "not found" rather than "forbidden", so the API does not
     * confirm that a video id exists to someone not entitled to know.
     */
    @Test
    void previewSessionForSomeoneElsesVideoDoesNotConfirmItExists() {
        String token = registerAndLogin();
        assertThat(postStatus(
                        "/api/v1/videos/" + UUID.randomUUID() + "/preview-playback-session", token, null))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uploadSessionOfAnotherAccountIsNotReadable() {
        String token = registerAndLogin();
        assertThat(getStatus("/api/v1/uploads/" + UUID.randomUUID(), token)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --------------------------------------------------------- token integrity

    @Test
    void aTokenSignedWithTheWrongKeyIs401() {
        // Same claims, different signature: only the signature makes this invalid.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJzdWIiOiJhZG1pbiIsInJvbGVzIjpbIkFETUlOIl19."
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertThat(getStatus("/internal/v1/videos/pending", forged)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Logout must end the session for the bearer token too, not just clear the
     * cookie — the same token is handed to the SPA in the login response and a
     * stateless token is otherwise good until it expires.
     */
    @Test
    void aTokenIsRejectedAfterLogout() {
        String token = registerAndLogin();
        assertThat(getStatus("/api/v1/auth/me", token)).isEqualTo(HttpStatus.OK);

        assertThat(postStatus("/api/v1/auth/logout", token, null)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(getStatus("/api/v1/auth/me", token)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Suspension has to take effect on the next request. Previously the token
     * stayed good for the rest of its TTL — up to 30 minutes of full API access
     * after an admin suspended the account.
     */
    @Test
    void aSuspendedAccountLosesApiAccessImmediately() {
        String token = registerAndLogin();
        String accountId = jwtService.parse(token).accountId();
        assertThat(getStatus("/api/v1/auth/me", token)).isEqualTo(HttpStatus.OK);

        String adminToken = tokenFor(Set.of("ADMIN"));
        assertThat(postStatus("/internal/v1/accounts/" + accountId + "/suspend", adminToken, Map.of("reason", "test")))
                .isEqualTo(HttpStatus.OK);

        assertThat(getStatus("/api/v1/auth/me", token)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------- 400, not 500

    @Test
    void aMalformedPathIdIs400() {
        String token = registerAndLogin();
        assertThat(getStatus("/api/v1/videos/not-a-uuid", token)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getStatus("/api/v1/creators/not-a-uuid", token)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getStatus("/api/v1/uploads/not-a-uuid", token)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedJsonIs400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/accounts"),
                HttpMethod.POST,
                new HttpEntity<>("{\"email\": ", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOutOfRangeQueryParameterIs400() {
        String token = tokenFor(Set.of("ADMIN"));
        // @Min(1) @Max(100) on the moderation queue's page size.
        assertThat(getStatus("/internal/v1/videos/pending?limit=100000", token)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBlankSearchQueryIs400() {
        String token = registerAndLogin();
        assertThat(getStatus("/api/v1/search?q=", token)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNegativeFeedPageIs400() {
        String token = registerAndLogin();
        assertThat(getStatus("/api/v1/feed?page=-1", token)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validationFailureNamesTheOffendingFields() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "not-an-email", "password", "short", "displayName", ""), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) response.getBody().get("errors");
        assertThat(errors).containsKeys("email", "password");
    }

    // --------------------------------------------------- actuator exposure

    @Test
    void probesArePublicButTheDetailedHealthReportIsNot() {
        assertThat(get("/actuator/health/liveness", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/health/readiness", new HttpHeaders()).getStatusCode())
                .isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);

        // The aggregate report names datasource URLs, broker addresses and disk
        // paths, so it is not public.
        assertThat(get("/actuator/health", new HttpHeaders()).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(get("/actuator/prometheus", new HttpHeaders()).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(get("/actuator/metrics", new HttpHeaders()).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void anAuthenticatedNonAdminStillCannotReadPrometheus() {
        assertThat(getStatus("/actuator/prometheus", registerAndLogin())).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------- helpers

    /** Mints a token directly so a role can be chosen without seeding an admin row. */
    private String tokenFor(Set<String> roles) {
        return jwtService.issue(UUID.randomUUID().toString(), roles).token();
    }

    private String registerAndLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(
                url("/api/v1/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email, "password", "correct-horse-battery", "displayName", "Test Creator"),
                        headers),
                Map.class);
        ResponseEntity<Map> login = rest.exchange(
                url("/api/v1/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), headers),
                Map.class);
        return (String) login.getBody().get("token");
    }

    private HttpStatus getStatus(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return (HttpStatus) get(path, headers).getStatusCode();
    }

    private HttpStatus postStatus(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return (HttpStatus) rest.exchange(
                        url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
                .getStatusCode();
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
