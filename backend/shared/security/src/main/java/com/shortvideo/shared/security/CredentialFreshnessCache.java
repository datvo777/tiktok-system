package com.shortvideo.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-through Redis cache in front of {@link CredentialFreshnessReader}.
 *
 * <p>Password changes are rare compared to authenticated requests, so this stays
 * a high-hit-rate cache: {@link #put} is called once, write-through, right after
 * the account row commits; every other read is a cache hit until the TTL below
 * expires. The TTL exists only as a safety net against a lost write-through (a
 * crash between the DB commit and this update) — it self-heals from the durable
 * row rather than staying wrong indefinitely.
 *
 * <p>Absence here is never "no password change happened" — a cache miss, or
 * Redis itself being unreachable, means "ask {@link CredentialFreshnessReader}",
 * never "assume fresh". Failing that read open would silently undo the whole
 * point of this check.
 */
@Component
public class CredentialFreshnessCache {

    private static final Logger log = LoggerFactory.getLogger(CredentialFreshnessCache.class);
    private static final String KEY_PREFIX = "account:pwd-changed-at:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public CredentialFreshnessCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Instant> get(String accountId) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + accountId);
            return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (RuntimeException e) {
            log.warn("Credential-freshness cache unavailable for {}; falling back to the durable read", accountId, e);
            return Optional.empty();
        }
    }

    public void put(String accountId, Instant changedAt) {
        try {
            redis.opsForValue().set(KEY_PREFIX + accountId, changedAt.toString(), TTL);
        } catch (RuntimeException e) {
            // Best-effort: the account row is authoritative and the next read
            // repopulates this from there.
            log.warn("Failed to write-through credential-freshness cache for {}", accountId, e);
        }
    }
}
