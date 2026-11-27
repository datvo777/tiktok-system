package com.shortvideo.app;

import com.shortvideo.shared.security.JwtProperties;
import com.shortvideo.shared.security.JwtService;
import com.shortvideo.shared.security.TokenKeys;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * Base for tests that need a Redis of their own: one they can stop, or one nothing
 * else is writing to. Each subclass holds its own container, so stopping Redis in one
 * test class cannot reach another.
 */
abstract class AbstractAuthRedisIT extends AbstractDatabaseIT {

    static final String PASSWORD = "correct-horse-battery";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired JwtProperties jwtProperties;
    @Autowired TokenKeys tokenKeys;

    @LocalServerPort int port;

    @SuppressWarnings("resource")
    static GenericContainer<?> newRedis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }

    static void pointAt(DynamicPropertyRegistry registry, GenericContainer<?> redis) {
        if (!redis.isRunning()) {
            redis.start();
        }
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Repeated logins from loopback would otherwise trip the limiter.
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
    }

    /** A registered account and the bearer token its login returned. */
    record Session(String accountId, String token) {}

    Session registerAndLogin() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        String email = "it-" + UUID.randomUUID() + "@example.com";
        Map<?, ?> created = rest.exchange(
                        url("/api/v1/accounts"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", PASSWORD, "displayName", "It Viewer"), json),
                        Map.class)
                .getBody();
        Map<?, ?> login = rest.exchange(
                        url("/api/v1/auth/login"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("email", email, "password", PASSWORD), json),
                        Map.class)
                .getBody();
        return new Session((String) created.get("accountId"), (String) login.get("token"));
    }

    /** A token for a real account holding {@code roles}; the filter re-derives roles from the account row. */
    String tokenWithRoles(Set<String> roles) {
        Session account = registerAndLogin();
        jdbc.update(
                "UPDATE account.account SET roles = ? WHERE account_id = ?::uuid",
                String.join(",", roles),
                account.accountId());
        return jwtService.issue(account.accountId(), roles).token();
    }

    /** Correctly signed for a real account, but already past its expiry. */
    String expiredTokenFor(String accountId) {
        JwtProperties past = new JwtProperties();
        past.setSecret(jwtProperties.getSecret());
        past.setIssuer(jwtProperties.getIssuer());
        past.setAudience(jwtProperties.getAudience());
        past.setTtl(Duration.ofMinutes(-5));
        return new JwtService(past, tokenKeys).issue(accountId, Set.of("USER")).token();
    }

    HttpStatus status(HttpMethod method, String path, String token) {
        return (HttpStatus) exchange(method, path, token).getStatusCode();
    }

    ResponseEntity<String> exchange(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), method, new HttpEntity<>(headers), String.class);
    }

    HttpStatus suspend(String adminToken, String accountId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return (HttpStatus) rest.exchange(
                        url("/internal/v1/accounts/" + accountId + "/suspend"),
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("reason", "it"), headers),
                        String.class)
                .getStatusCode();
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }
}
