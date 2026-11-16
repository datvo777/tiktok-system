package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.security.SessionDenyListRebuilder;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Signing out must not depend on Redis remembering it. The deny-list is written to PostgreSQL first and
 * Redis is only the fast path, so a Redis that is emptied (restart, flush, eviction) or unreachable
 * cannot make a signed-out token work again, and a refresh cannot be redeemed twice.
 */
class SessionDenyListDurabilityIT extends AbstractAuthRedisIT {

    private static final GenericContainer<?> REDIS = newRedis();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        pointAt(registry, REDIS);
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired StringRedisTemplate redis;
    @Autowired SessionDenyListRebuilder rebuilder;

    @Test
    void aSignedOutTokenStaysRefusedAfterRedisIsEmptied() {
        Session viewer = registerAndLogin();
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", viewer.token())).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.POST, "/api/v1/auth/logout", viewer.token())).isEqualTo(HttpStatus.NO_CONTENT);

        flushRedis();

        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", viewer.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theRebuildRefillsRedisAndRestoresTheFastPath() {
        Session viewer = registerAndLogin();
        status(HttpMethod.POST, "/api/v1/auth/logout", viewer.token());
        flushRedis();
        assertThat(redis.keys("session:revoked:*")).isEmpty();

        rebuilder.rebuild();

        assertThat(redis.keys("session:revoked:*")).hasSizeGreaterThanOrEqualTo(2); // the entry and the marker
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", viewer.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        // A token that was never revoked is not refused once the marker says Redis is complete.
        assertThat(status(HttpMethod.GET, "/api/v1/auth/me", registerAndLogin().token())).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aRefreshedTokenCannotBeRedeemedAgainEvenAfterRedisIsEmptied() {
        Session viewer = registerAndLogin();
        assertThat(status(HttpMethod.POST, "/api/v1/auth/refresh", viewer.token())).isEqualTo(HttpStatus.OK);

        flushRedis();

        assertThat(status(HttpMethod.POST, "/api/v1/auth/refresh", viewer.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ofTwoConcurrentRefreshesOfOneTokenExactlyOneWins() throws Exception {
        Session viewer = registerAndLogin();
        flushRedis(); // the claim must not need Redis
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpStatus> refresh = () -> status(HttpMethod.POST, "/api/v1/auth/refresh", viewer.token());
            List<Future<HttpStatus>> results = pool.invokeAll(List.of(refresh, refresh));

            assertThat(results.stream().map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void revocationsAreRecordedInPostgresAndExpiredOnesAreRemoved() {
        Session viewer = registerAndLogin();
        status(HttpMethod.POST, "/api/v1/auth/logout", viewer.token());
        Integer recorded = jdbc.queryForObject("SELECT count(*) FROM platform.revoked_session", Integer.class);
        assertThat(recorded).isGreaterThanOrEqualTo(1);

        jdbc.update("UPDATE platform.revoked_session SET expires_at = now() - interval '1 minute'");
        rebuilder.rebuild();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.revoked_session", Integer.class)).isZero();
    }

    private void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }
}
