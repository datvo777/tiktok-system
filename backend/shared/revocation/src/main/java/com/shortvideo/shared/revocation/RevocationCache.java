package com.shortvideo.shared.revocation;

import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis deny-only acceleration for revocation (brief section 16, Rule 12).
 *
 * <p>One hash per subject, one field per restrictive source
 * ({@code revocation:video:{videoId}}, {@code revocation:account:{accountId}}).
 * Field presence means active; a permissive change removes only its own field
 * (never the whole key) so other active sources on the same subject survive.
 *
 * <p>Absence of a key, or of Redis itself, is never treated as permission — callers
 * use {@link #isDenied} only as a fast deny path and always fall back to the
 * durable PostgreSQL record before allowing anything.
 */
@Component
public class RevocationCache {

    private final StringRedisTemplate redis;

    public RevocationCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return true only when Redis is reachable and reports an active restriction. */
    public boolean isDenied(String subjectType, String subjectId) {
        try {
            Long size = redis.opsForHash().size(key(subjectType, subjectId));
            return size != null && size > 0;
        } catch (RuntimeException e) {
            // Redis is an acceleration path only: an outage here is not a deny
            // signal, it just skips straight to the durable check.
            return false;
        }
    }

    void putActive(String subjectType, String subjectId, String sourceType, String reason) {
        try {
            redis.opsForHash().put(key(subjectType, subjectId), sourceType, reason == null ? "revoked" : reason);
        } catch (RuntimeException ignored) {
            // Best-effort: the durable record is authoritative and will be found
            // on the next read even if this cache write failed.
        }
    }

    void clearField(String subjectType, String subjectId, String sourceType) {
        try {
            redis.opsForHash().delete(key(subjectType, subjectId), sourceType);
        } catch (RuntimeException ignored) {
            // Same reasoning as putActive: durable state is authoritative.
        }
    }

    /**
     * Removes any Redis field not present in {@code authoritative} for this
     * subject — a stray field means a permissive change's cache update was lost
     * (e.g. a crash between the durable clear and the best-effort Redis write).
     * PostgreSQL is authoritative, so the fix is always "make Redis match it."
     *
     * @return the number of stray fields removed (brief section 20, Milestone 5 drift check)
     */
    int reconcileSubject(String subjectType, String subjectId, Set<String> authoritativeSourceTypes) {
        try {
            String key = key(subjectType, subjectId);
            Set<Object> cachedFields = redis.opsForHash().keys(key);
            int removed = 0;
            for (Object field : cachedFields) {
                if (!authoritativeSourceTypes.contains(field.toString())) {
                    redis.opsForHash().delete(key, field);
                    removed++;
                }
            }
            return removed;
        } catch (RuntimeException e) {
            return 0; // best-effort; the next rebuild pass tries again
        }
    }

    /** All subject keys currently cached, so a rebuild can find stray subjects with no active revocation left at all. */
    Set<String> allCachedSubjectKeys() {
        try {
            return redis.keys("revocation:*");
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    void deleteWholeKey(String cacheKey) {
        try {
            redis.delete(cacheKey);
        } catch (RuntimeException ignored) {
            // Best-effort; a future rebuild pass retries.
        }
    }

    Map.Entry<String, String> parseKey(String cacheKey) {
        String[] parts = cacheKey.split(":", 3);
        return Map.entry(parts[1].toUpperCase(java.util.Locale.ROOT), parts[2]);
    }

    private String key(String subjectType, String subjectId) {
        return "revocation:" + subjectType.toLowerCase(java.util.Locale.ROOT) + ":" + subjectId;
    }
}
