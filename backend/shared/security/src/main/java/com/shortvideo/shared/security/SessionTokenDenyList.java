package com.shortvideo.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes logout actually end a session (brief section 12.1).
 *
 * <p>A stateless bearer token is valid until it expires, so clearing the cookie
 * only ends the session for a client that cooperates. The token returned in the
 * login response body keeps working for the rest of its TTL — up to 30 minutes of
 * full API access after the user pressed "Log out", including on a shared or
 * stolen device.
 *
 * <p><b>Two stores.</b> Each revocation is written to PostgreSQL first
 * ({@link RevokedSessionStore}, authoritative) and then to Redis, keyed by the token's
 * {@code jti}, which answers the per-request question without a query. Redis alone was
 * not enough: it keeps nothing across a restart or a flush, so every token signed out
 * in the last lifetime became valid again, and the single-use claim of a refresh could
 * be taken twice. Entries expire on their own, slightly after the token they revoke, so
 * both stores stay bounded by the logouts within one token lifetime.
 *
 * <p><b>When a Redis miss means "not revoked".</b> Only while Redis is known to hold
 * every unexpired revocation. {@link SessionDenyListRebuilder} copies them in after a
 * start and then on a schedule, and only then writes a completeness marker; a restarted
 * or flushed Redis has no marker, and until the next rebuild a miss is checked against
 * PostgreSQL instead. If Redis cannot be read at all the same fallback applies. Only when
 * PostgreSQL cannot be read either does the request pass (the checks that follow in the
 * authentication filter read PostgreSQL too and refuse then). A write to Redis that fails
 * after PostgreSQL accepted the revocation is not an error: the rebuild copies it within
 * one interval.
 */
@Component
public class SessionTokenDenyList {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenDenyList.class);
    static final String KEY_PREFIX = "session:revoked:";
    /** Present while Redis holds every unexpired revocation; a jti is a UUID, so it cannot collide. */
    static final String COMPLETE_MARKER = KEY_PREFIX + "complete";

    private final StringRedisTemplate redis;
    private final JwtProperties properties;
    private final RevokedSessionStore store;

    public SessionTokenDenyList(StringRedisTemplate redis, JwtProperties properties, RevokedSessionStore store) {
        this.redis = redis;
        this.properties = properties;
        this.store = store;
    }

    /** Revokes one token for the remainder of its lifetime. */
    public void revoke(String tokenId, Instant expiresAt) {
        Instant until = withMargin(expiresAt);
        if (!until.isAfter(Instant.now())) {
            return; // already expired; nothing to revoke
        }
        store.insertIfAbsent(tokenId, until);
        cache(tokenId, until);
    }

    /**
     * Atomically revokes the token only if nobody has yet, so of two concurrent
     * holders of the same token exactly one gets {@code true}. Used by {@code /refresh}
     * to keep rotation single-use: the loser must not mint a second live credential.
     * The claim is the PostgreSQL insert, so it holds across a Redis restart.
     */
    public boolean revokeIfActive(String tokenId, Instant expiresAt) {
        Instant until = withMargin(expiresAt);
        if (!until.isAfter(Instant.now())) {
            return false;
        }
        if (!store.insertIfAbsent(tokenId, until)) {
            return false;
        }
        cache(tokenId, until);
        return true;
    }

    public boolean isRevoked(String tokenId) {
        try {
            List<String> found = redis.opsForValue().multiGet(List.of(KEY_PREFIX + tokenId, COMPLETE_MARKER));
            if (found != null && found.get(0) != null) {
                return true;
            }
            if (found != null && found.get(1) != null) {
                return false; // Redis holds every revocation, and this token is not among them
            }
        } catch (RuntimeException e) {
            log.warn("Session deny-list cache unavailable; asking PostgreSQL about token {}", tokenId, e);
        }
        try {
            return store.isRevoked(tokenId);
        } catch (RuntimeException e) {
            log.warn("Session deny-list unavailable in both stores; allowing token {} on its remaining TTL", tokenId, e);
            return false;
        }
    }

    /** Puts one revocation into Redis for the time that remains, failing if Redis does not take it. */
    void restore(String tokenId, Instant until) {
        Duration remaining = Duration.between(Instant.now(), until);
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + tokenId, "1", remaining);
    }

    /** As {@link #restore}, for a revocation just recorded in PostgreSQL: a failure is repaired by the next rebuild. */
    private void cache(String tokenId, Instant until) {
        try {
            restore(tokenId, until);
        } catch (RuntimeException e) {
            log.warn("Could not cache the revocation of token {}; it is recorded in PostgreSQL", tokenId, e);
        }
    }

    void markComplete() {
        redis.opsForValue().set(COMPLETE_MARKER, "1");
    }

    /**
     * A small margin over the token's own expiry closes the gap left by clock skew between
     * this process and whichever one validates the token next.
     */
    private static Instant withMargin(Instant expiresAt) {
        return expiresAt.plusSeconds(60);
    }

    /** Exposed so callers can size their own expectations against the configured TTL. */
    public Duration retention() {
        return properties.revocationRetention();
    }
}
