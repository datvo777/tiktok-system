package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opening streams past the instance's budget is refused at the door, before authentication: the
 * refusal is a 503 with a Retry-After, and it comes even for a request that carries no session at
 * all, which proves it never reached the filter that talks to Redis and PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealtimeOpenGateIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("shortvideo.login-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.register-rate-limit.enabled", () -> "false");
        registry.add("shortvideo.realtime.enabled", () -> "true");
        // Three opens in a burst, then one a second.
        registry.add("shortvideo.realtime.open-burst", () -> "3");
        registry.add("shortvideo.realtime.max-opens-per-second", () -> "1");
        registry.add("shortvideo.realtime.heartbeat-interval", () -> "1h");
        registry.add("shortvideo.realtime.revalidate-interval", () -> "1h");
    }

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    @Test
    void opensPastTheBudgetAreRefusedWithRetryAfterEvenWithoutASession() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        List<Integer> statuses = new ArrayList<>();
        String retryAfter = null;
        for (int i = 0; i < 8; i++) {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events/stream")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            statuses.add(response.statusCode());
            if (response.statusCode() == 503) {
                retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            }
        }

        // The first three got as far as authentication (and were told to sign in); the rest never did.
        assertThat(statuses.subList(0, 3)).containsOnly(401);
        assertThat(statuses.subList(3, 8)).contains(503);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 15L);
    }

    @Test
    void otherRoutesAreNotCountedAgainstTheBudget() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < 10; i++) {
            var response = rest.exchange(
                    "http://localhost:" + port + "/api/v1/accounts",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            Map.of("email", "gate-" + UUID.randomUUID() + "@example.com",
                                    "password", "correct-horse-battery", "displayName", "Gate"),
                            json),
                    Map.class);
            assertThat(response.getStatusCode().value()).isEqualTo(201);
        }
    }
}
