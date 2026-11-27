package com.shortvideo.feed.domain;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The Redis the ranking cache talks to: the application's own when
 * {@code shortvideo.feed.cache-redis-host} is unset, a dedicated one when it is.
 *
 * <p>Deliberately not exposed as a {@code StringRedisTemplate} bean or a
 * {@code RedisConnectionFactory} bean: either would switch off Spring Boot's
 * auto-configured pair and silently repoint every other Redis user (the logout
 * deny-list, rate limiters, revocation cache) at this one. The dedicated
 * connection is owned here and closed here.
 */
@Component
class FeedCacheRedis implements InitializingBean, DisposableBean {

    private final StringRedisTemplate shared;
    private final FeedProperties properties;
    private LettuceConnectionFactory own;
    private StringRedisTemplate template;

    FeedCacheRedis(StringRedisTemplate shared, FeedProperties properties) {
        this.shared = shared;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String host = properties.getCacheRedisHost();
        if (host == null || host.isBlank()) {
            template = shared;
            return;
        }
        // Same bound on a slow Redis as the shared connection's 2s timeout: a cache
        // that hangs is worse than one that is skipped.
        own = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, properties.getCacheRedisPort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        own.afterPropertiesSet();
        template = new StringRedisTemplate((RedisConnectionFactory) own);
    }

    StringRedisTemplate template() {
        return template;
    }

    @Override
    public void destroy() {
        if (own != null) {
            own.destroy();
        }
    }
}
