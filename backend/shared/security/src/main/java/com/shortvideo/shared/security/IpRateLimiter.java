package com.shortvideo.shared.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A fixed-window Redis counter, shared by every per-request rate limiter
 * ({@link LoginRateLimiter}, {@link RegisterRateLimiter}) so each one only
 * supplies its own key prefix and thresholds.
 *
 * <p>Fixed windows rather than a token bucket: the boundary burst a fixed
 * window allows is irrelevant at these thresholds, and it needs no new
 * dependency — one INCR and one EXPIRE against the Redis already in the stack.
 *
 * <p><b>Fails open.</b> If Redis is unavailable the attempt is allowed, so a
 * cache blip degrades to "the throttle is temporarily absent" rather than
 * turning into a total outage of the endpoint it guards.
 */
@Component
public class IpRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimiter.class);

    private final StringRedisTemplate redis;

    public IpRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return the count after incrementing, or 0 when Redis is unreachable (fail open). */
    public long increment(String key, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // First hit in this window starts the clock. Setting the expiry on
                // every hit would slide the window forward indefinitely under
                // sustained load and the key would never expire.
                redis.expire(key, window);
            }
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable for key {}; allowing attempt: {}", key, e.getMessage());
            return 0;
        }
    }

    /** @return the current count without incrementing, or null when unreadable. */
    public Long peek(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : Long.parseLong(value);
        } catch (RuntimeException e) {
            // Covers both an unreachable Redis and a non-numeric value; either way
            // there is no usable count, and the caller should fail open.
            return null;
        }
    }

    /** Clears a counter, e.g. on a successful attempt that should not count against later ones. */
    public void clear(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.debug("Could not clear rate limiter key {}: {}", key, e.getMessage());
        }
    }
}
