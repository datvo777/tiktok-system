package com.shortvideo.shared.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-through Redis cache in front of {@link RoleFreshnessReader}, same shape and
 * failure semantics as {@link CredentialFreshnessCache}: a miss or unreachable Redis
 * means "ask the durable row", never "trust the token's roles".
 *
 * <p>Whatever changes an account's roles must call {@link #put} (or {@link #evict})
 * after its transaction commits, so the change applies on the very next request. The
 * TTL is only the safety net for a lost write-through.
 */
@Component
public class RoleFreshnessCache {

    private static final Logger log = LoggerFactory.getLogger(RoleFreshnessCache.class);
    private static final String KEY_PREFIX = "account:roles:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public RoleFreshnessCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Set<String>> get(String accountId) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + accountId);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Arrays.stream(value.split(",")).collect(Collectors.toUnmodifiableSet()));
        } catch (RuntimeException e) {
            log.warn("Role-freshness cache unavailable for {}; falling back to the durable read", accountId, e);
            return Optional.empty();
        }
    }

    public void put(String accountId, Set<String> roles) {
        try {
            redis.opsForValue().set(KEY_PREFIX + accountId, String.join(",", new TreeSet<>(roles)), TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to write-through role-freshness cache for {}", accountId, e);
        }
    }

    public void evict(String accountId) {
        try {
            redis.delete(KEY_PREFIX + accountId);
        } catch (RuntimeException e) {
            log.warn("Failed to evict role-freshness cache for {}", accountId, e);
        }
    }
}
