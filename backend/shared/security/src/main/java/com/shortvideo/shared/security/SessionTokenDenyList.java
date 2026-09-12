package com.shortvideo.shared.security;

import java.time.Duration;
import java.time.Instant;
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
 * <p>Each entry is keyed by the token's {@code jti} and expires on its own,
 * slightly after the token it revokes, so the list stays bounded by the number of
 * logouts within one token lifetime rather than growing forever.
 *
 * <p>Redis being unavailable must not become an authentication outage, so a
 * failure to <em>read</em> the list allows the request: the deny-list shortens a
 * session that would otherwise have remained valid anyway, and the authoritative
 * account-level check in {@link JwtAuthenticationFilter} is what fails closed. A
 * failure to <em>write</em> is surfaced to the caller, because silently reporting
 * a successful logout that did not revoke anything is worse than an error.
 */
@Component
public class SessionTokenDenyList {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenDenyList.class);
    private static final String KEY_PREFIX = "session:revoked:";

    private final StringRedisTemplate redis;
    private final JwtProperties properties;

    public SessionTokenDenyList(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Revokes one token for the remainder of its lifetime. */
    public void revoke(String tokenId, Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return; // already expired; nothing to revoke
        }
        // A small margin over the token's own expiry closes the gap left by clock
        // skew between this process and whichever one validates the token next.
        redis.opsForValue().set(KEY_PREFIX + tokenId, "1", remaining.plusSeconds(60));
    }

    public boolean isRevoked(String tokenId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException e) {
            log.warn("Session deny-list unavailable; allowing token {} on its remaining TTL", tokenId, e);
            return false;
        }
    }

    /** Exposed so callers can size their own expectations against the configured TTL. */
    public Duration retention() {
        return properties.revocationRetention();
    }
}
