package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.moderation.api.PolicyCategory;
import com.shortvideo.moderation.domain.ModerationService;
import com.shortvideo.shared.security.JwtService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The appeal journey over HTTP, as a creator and an admin experience it: the real filter chain, real
 * accounts and roles, the controllers and the exception handler in front of the service.
 *
 * <p>The rules themselves (every state, every race) are covered by {@link AppealServiceIT}; this
 * class pins what only the web layer can get wrong: that the caller taken from the token is the one
 * the rule is applied to, and what status each refusal turns into.
 *
 * <p>The rejected video is set up through the services rather than the upload pipeline, which needs
 * object storage and a broker that have nothing to do with appeals.
 */
class AppealFlowIT extends AbstractDatabaseIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ModerationService moderation;
    @Autowired StringRedisTemplate redis;

    @LocalServerPort int port;

    @BeforeEach
    void clearRegistrationCounters() {
        // Per-IP counters are shared by every test: they all come from loopback.
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
    }

    @Test
    void aCreatorAppealsARejectedVideoAndAnAdminApprovesIt() {
        Account creator = account(Set.of("USER"));
        Account admin = account(Set.of("USER", "ADMIN"));
        String video = rejectedVideoOf(creator);

        ResponseEntity<Map> submitted = post("/api/v1/videos/" + video + "/appeals", creator, Map.of("reason", "mistake"));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody()).containsEntry("state", "UNDER_APPEAL");

        List<Map<String, Object>> pending = getList("/internal/v1/appeals/pending", admin);
        Map<String, Object> shown = pending.stream()
                .filter(a -> video.equals(a.get("videoId")))
                .findFirst()
                .orElseThrow();

        ResponseEntity<Map> decided = post(
                "/internal/v1/appeals/" + video + "/approve",
                admin,
                Map.of("reason", "fair", "expectedVersion", shown.get("version")));
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decided.getBody()).containsEntry("state", "APPROVED");

        ResponseEntity<Map> seenByCreator = get("/api/v1/videos/" + video + "/appeals", creator);
        assertThat(seenByCreator.getBody()).containsEntry("state", "APPROVED");
    }

    /** The invariant is in the service; this proves the controller hands it the caller from the token. */
    @Test
    void anAdminCannotDecideAnAppealOfTheirOwnVideoEvenWithTheRightVersion() {
        Account admin = account(Set.of("USER", "ADMIN"));
        String video = rejectedVideoOf(admin);
        post("/api/v1/videos/" + video + "/appeals", admin, Map.of("reason", "mine"));
        Object version = get("/api/v1/videos/" + video + "/appeals", admin).getBody().get("version");
        Map<String, Object> decision = Map.of("reason", "fine by me", "expectedVersion", version);

        assertThat(post("/internal/v1/appeals/" + video + "/approve", admin, decision).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/internal/v1/appeals/" + video + "/deny", admin, decision).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/videos/" + video + "/appeals", admin).getBody()).containsEntry("state", "UNDER_APPEAL");
    }

    @Test
    void someoneElsesVideoAnswers404RatherThanRevealingItExists() {
        String video = rejectedVideoOf(account(Set.of("USER")));

        assertThat(post("/api/v1/videos/" + video + "/appeals", account(Set.of("USER")), Map.of("reason", "not mine"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSecondAppealWhileOneIsUnderReviewIs409() {
        Account creator = account(Set.of("USER"));
        String video = rejectedVideoOf(creator);
        post("/api/v1/videos/" + video + "/appeals", creator, Map.of("reason", "first"));

        assertThat(post("/api/v1/videos/" + video + "/appeals", creator, Map.of("reason", "again")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aCreatorCannotUseTheAdminSurface() {
        Account creator = account(Set.of("USER"));

        assertThat(get("/internal/v1/appeals/pending", creator).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------------ fixtures

    private record Account(String id, String token) {}

    /** A real account holding {@code roles}: the filter takes roles from the row, not from the token. */
    private Account account(Set<String> roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> created = rest.exchange(
                        url("/api/v1/accounts"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "email", "appeal-" + UUID.randomUUID() + "@example.com",
                                        "password", "correct-horse-battery",
                                        "displayName", "Appeal Tester"),
                                headers),
                        Map.class)
                .getBody();
        String id = (String) created.get("accountId");
        jdbc.update("UPDATE account.account SET roles = ? WHERE account_id = ?::uuid", String.join(",", roles), id);
        return new Account(id, jwtService.issue(id, roles).token());
    }

    private String rejectedVideoOf(Account owner) {
        String video = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO video.video (video_id, owner_account_id, processing_state, processing_version,"
                        + " durability_state, asset_lifecycle_state, legal_serving_state, created_at, updated_at)"
                        + " VALUES (?::uuid, ?::uuid, 'READY', 1, 'DURABLE', 'ACTIVE', 'CLEAR', now(), now())",
                video,
                owner.id());
        moderation.createPending(video, owner.id());
        moderation.reject(video, PolicyCategory.SPAM_DECEPTIVE, "spam", ModerationService.SYSTEM_ACTOR);
        return video;
    }

    // ---------------------------------------------------------------------------- HTTP

    private ResponseEntity<Map> post(String path, Account as, Object body) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(as)), Map.class);
    }

    private ResponseEntity<Map> get(String path, Account as) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(as)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path, Account as) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(as)), List.class)
                .getBody();
    }

    private static HttpHeaders headers(Account as) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(as.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
