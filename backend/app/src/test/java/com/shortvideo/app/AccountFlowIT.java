package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
 * Milestone 1 acceptance, end to end against a real PostgreSQL.
 *
 * <p>The media cases are the ones worth reading twice: a bearer token must NOT
 * authenticate a media request (Rule 17), because hls.js and the video element
 * cannot send headers. A test that passed a header here would "prove" a path the
 * browser can never exercise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AccountFlowIT {

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
        // Not reachable in this test; nothing here publishes, and the relay is off
        // under the "test" profile.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    private String email;

    @BeforeEach
    void setUp() {
        email = "creator-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void registersStoresABcryptHashAndNeverThePassword() {
        ResponseEntity<Map> created = register(email, "correct-horse-battery");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) created.getBody().get("accountId");

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM account.account WHERE account_id = ?::uuid", String.class, accountId);

        assertThat(hash).startsWith("$2");
        assertThat(hash).doesNotContain("correct-horse-battery");
    }

    @Test
    void rejectsADuplicateEmail() {
        register(email, "correct-horse-battery");
        assertThat(register(email, "correct-horse-battery").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginReturnsATokenInTheBodyAndAnHttpOnlySessionCookie() {
        register(email, "correct-horse-battery");
        ResponseEntity<Map> login = login(email, "correct-horse-battery");

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) login.getBody().get("token")).isNotBlank();

        String setCookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("sv_session=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/");
    }

    @Test
    void badCredentialsAnswer401WithoutRevealingWhetherTheEmailExists() {
        register(email, "correct-horse-battery");

        ResponseEntity<Map> wrongPassword = login(email, "wrong-password-here");
        ResponseEntity<Map> unknownEmail = login("nobody-" + UUID.randomUUID() + "@example.com", "whatever-pass");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("detail")).isEqualTo(unknownEmail.getBody().get("detail"));
    }

    @Test
    void apiAcceptsEitherBearerOrCookieTransport() {
        register(email, "correct-horse-battery");
        ResponseEntity<Map> login = login(email, "correct-horse-battery");
        String accountId = (String) login.getBody().get("accountId");
        String token = (String) login.getBody().get("token");

        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(token);
        assertThat(get("/api/v1/accounts/" + accountId, bearer).getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders cookie = new HttpHeaders();
        cookie.add(HttpHeaders.COOKIE, "sv_session=" + token);
        assertThat(get("/api/v1/accounts/" + accountId, cookie).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidTokenIs401OnBothTransports() {
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth("not.a.valid.jwt");
        assertThat(get("/api/v1/accounts/" + UUID.randomUUID(), bearer).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders cookie = new HttpHeaders();
        cookie.add(HttpHeaders.COOKIE, "sv_session=not.a.valid.jwt");
        assertThat(get("/api/v1/accounts/" + UUID.randomUUID(), cookie).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mediaRejectsABearerTokenAndAcceptsOnlyTheCookie() {
        register(email, "correct-horse-battery");
        String token = (String) login(email, "correct-horse-battery").getBody().get("token");
        String path = "/media/videos/" + UUID.randomUUID() + "/1/master.m3u8";

        HttpHeaders bearerOnly = new HttpHeaders();
        bearerOnly.setBearerAuth(token);
        ResponseEntity<String> withHeader = get(path, bearerOnly);

        // Rule 17: the header transport does not exist for media.
        assertThat(withHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withHeader.getBody()).doesNotContain("#EXTM3U");

        HttpHeaders cookie = new HttpHeaders();
        cookie.add(HttpHeaders.COOKIE, "sv_session=" + token);
        ResponseEntity<String> withCookie = get(path, cookie);

        // Milestone 2: the session cookie alone never authorizes delivery either —
        // the gateway also requires a valid playback cookie (brief section 8).
        assertThat(withCookie.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withCookie.getBody()).doesNotContain("#EXTM3U");
    }

    @Test
    void mediaRejectsAnUnsupportedAssetPathWithoutBytes() {
        register(email, "correct-horse-battery");
        String token = (String) login(email, "correct-horse-battery").getBody().get("token");

        HttpHeaders cookie = new HttpHeaders();
        cookie.add(HttpHeaders.COOKIE, "sv_session=" + token);
        ResponseEntity<String> response =
                get("/media/videos/" + UUID.randomUUID() + "/1/payload.sh", cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthenticatedMediaRequestIs401() {
        ResponseEntity<String> response =
                get("/media/videos/" + UUID.randomUUID() + "/1/master.m3u8", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalEndpointsRequireTheAdminRole() {
        register(email, "correct-horse-battery");
        String token = (String) login(email, "correct-horse-battery").getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange(
                url("/internal/v1/accounts/" + UUID.randomUUID() + "/suspend"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "test"), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void registrationWritesExactlyOneOutboxEventInTheSameTransaction() {
        String accountId = (String) register(email, "correct-horse-battery").getBody().get("accountId");

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_type = 'ACCOUNT' AND aggregate_id = ?",
                Integer.class,
                accountId);

        assertThat(events).isEqualTo(1);
    }

    @Test
    void livenessIsUpAndFlywayCreatedEveryModuleSchema() {
        assertThat(get("/actuator/health/liveness", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer schemas = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata "
                        + "WHERE schema_name IN ('platform', 'account', 'upload', 'video', 'eligibility', "
                        + "'moderation', 'publication')",
                Integer.class);

        assertThat(schemas).isEqualTo(7);
    }

    private ResponseEntity<Map> register(String address, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body =
                Map.of("email", address, "password", password, "displayName", "Test Creator");
        return rest.exchange(
                url("/api/v1/accounts"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> login(String address, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("email", address, "password", password);
        return rest.exchange(
                url("/api/v1/auth/login"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
