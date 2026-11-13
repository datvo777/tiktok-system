package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * What a Redis outage does to authentication and to the feed. Redis is stopped for real,
 * so this class owns its container and contains a single test: once Redis is gone nothing
 * after it in the class could rely on it.
 *
 * <p>The design being pinned: the logout deny-list is an accelerator that fails open (the
 * token is good for its remaining TTL anyway), while the account-level check falls back to
 * PostgreSQL and so still refuses a suspended account.
 */
class RedisOutageAuthIT extends AbstractAuthRedisIT {

    private static final GenericContainer<?> REDIS = newRedis();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        pointAt(registry, REDIS);
    }

    @AfterAll
    static void stopRedis() {
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @Test
    void withRedisDownALoggedOutTokenWorksAgainButASuspendedAccountStaysRefusedAndTheFeedStillServes() {
        Session loggedOut = registerAndLogin();
        Session suspended = registerAndLogin();
        Session healthy = registerAndLogin();
        String admin = tokenWithRoles(Set.of("ADMIN"));

        assertThat(status(HttpMethod.POST, "/api/v1/auth/logout", loggedOut.token())).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(suspend(admin, suspended.accountId())).isEqualTo(HttpStatus.OK);
        // Sanity, with Redis up: both are refused, the healthy one is not.
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", loggedOut.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", suspended.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", healthy.token())).isEqualTo(HttpStatus.OK);

        REDIS.stop();

        // Fail-open by design: the deny-list cannot be read, so the logged-out token is honoured again.
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", loggedOut.token())).isEqualTo(HttpStatus.OK);
        // Fail-closed where it matters: the durable PostgreSQL record still refuses the suspended account.
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", suspended.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", healthy.token())).isEqualTo(HttpStatus.OK);

        // The feed is a derived read model: no cache, but still an answer.
        assertThat(status(HttpMethod.GET, "/api/v1/feed", null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/feed?page=1", healthy.token())).isEqualTo(HttpStatus.OK);
    }
}
