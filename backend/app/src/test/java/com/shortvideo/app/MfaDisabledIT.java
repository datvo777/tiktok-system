package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With {@code shortvideo.mfa.enabled} off, which is the default, nothing about signing in or /internal changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MfaDisabledIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.register-rate-limit.enabled", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearCounters() {
        redis.keys("login:*").forEach(redis::delete);
        redis.keys("register:*").forEach(redis::delete);
    }

    @Test
    void anAdminWithOnlyAPasswordReachesInternalAsBefore() {
        String email = "off-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/v1/accounts", Map.of("email", email, "password", "correct-horse-battery", "displayName", "Off"), Map.class);
        jdbc.update("UPDATE account.account SET roles = 'USER,ADMIN' WHERE email = ?", email);
        ResponseEntity<Map> login = rest.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery"), Map.class);
        assertThat(login.getBody()).containsKey("token").doesNotContainKey("mfaRequired");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("token"));
        ResponseEntity<String> internal = rest.exchange(
                "/internal/v1/videos/pending", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(internal.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theSecondFactorRoutesDoNotExist() {
        assertThat(rest.postForEntity("/api/v1/auth/mfa/verify", Map.of("mfaToken", "x", "code", "123456"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
