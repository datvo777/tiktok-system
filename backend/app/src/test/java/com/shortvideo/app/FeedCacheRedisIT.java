package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * With a dedicated cache Redis configured, rankings go there and nowhere else: the
 * application's own Redis, which holds the logout deny-list and must never be under
 * eviction pressure from rankings, receives none of them.
 */
class FeedCacheRedisIT extends AbstractAuthRedisIT {

    private static final GenericContainer<?> MAIN = newRedis();
    private static final GenericContainer<?> CACHE = newRedis();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        pointAt(registry, MAIN);
        CACHE.start();
        registry.add("shortvideo.feed.cache-redis-host", CACHE::getHost);
        registry.add("shortvideo.feed.cache-redis-port", () -> CACHE.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        MAIN.stop();
        CACHE.stop();
    }

    @Test
    void rankingsLandInTheCacheRedisAndNotInTheApplicationsOwn() throws Exception {
        Session viewer = registerAndLogin();

        assertThat(status(HttpMethod.GET, "/api/v1/feed", null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/feed", viewer.token())).isEqualTo(HttpStatus.OK);

        assertThat(keys(CACHE)).contains("feed:ranking:anonymous", "feed:ranking:" + viewer.accountId());
        assertThat(keys(MAIN)).doesNotContain("feed:ranking:");
    }

    private static String keys(GenericContainer<?> redis) throws Exception {
        return redis.execInContainer("redis-cli", "--scan", "--pattern", "feed:ranking:*").getStdout();
    }
}
