package com.shortvideo.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rate limits authentication attempts (brief section 12.1).
 *
 * <p>Verifying a password costs a deliberate ~100ms of BCrypt, which is a defence
 * against offline cracking and a liability online: an unauthenticated endpoint
 * that burns 100ms of CPU per request is a cheap denial-of-service as well as an
 * open door for credential stuffing. Two independent counters close both:
 *
 * <ul>
 *   <li><b>Per IP, counting every attempt.</b> Bounds the CPU one caller can
 *       consume regardless of which accounts they aim at, which is what makes
 *       credential stuffing across many usernames expensive.
 *   <li><b>Per account, counting only failures and cleared on success.</b>
 *       Bounds guessing against one account without letting someone lock a
 *       victim out by repeatedly submitting their address — a legitimate user's
 *       own successful logins never count against them.
 * </ul>
 *
 * <p>Fixed windows rather than a token bucket: the boundary burst a fixed window
 * allows (up to 2x the limit across a window edge) is irrelevant at these
 * thresholds, and it needs no new dependency — this is one INCR and one EXPIRE
 * against the Redis already in the stack.
 *
 * <p><b>Fails open.</b> If Redis is unavailable the attempt is allowed. That is
 * the opposite of the revocation check in {@link JwtAuthenticationFilter}, which
 * fails closed, and the asymmetry is deliberate: refusing a revoked token when
 * state is unknown protects the system, whereas refusing <em>all</em> logins
 * because a cache is down converts a Redis blip into a total outage. A limiter
 * that is down means the protection is temporarily absent, not that everyone is
 * locked out.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String IP_PREFIX = "login:ip:";
    private static final String ACCOUNT_PREFIX = "login:account:";

    private final IpRateLimiter counter;
    private final LoginRateLimitProperties properties;

    public LoginRateLimiter(IpRateLimiter counter, LoginRateLimitProperties properties) {
        this.counter = counter;
        this.properties = properties;
    }

    /**
     * Called before the password is verified, so a throttled attempt costs no
     * BCrypt at all — checking after the hash would defeat the point.
     *
     * @throws TooManyLoginAttemptsException carrying how long to wait
     */
    public void checkAllowed(String clientIp, String email) {
        if (!properties.isEnabled()) {
            return;
        }
        long ipAttempts = counter.increment(IP_PREFIX + clientIp, properties.getIpWindow());
        if (ipAttempts > properties.getMaxAttemptsPerIp()) {
            log.warn("Throttled login attempts from {}", clientIp);
            throw new TooManyLoginAttemptsException(properties.getIpWindow());
        }
        Long accountFailures = counter.peek(ACCOUNT_PREFIX + email);
        if (accountFailures != null && accountFailures >= properties.getMaxFailuresPerAccount()) {
            throw new TooManyLoginAttemptsException(properties.getAccountWindow());
        }
    }

    /** Counts one failed verification against the account. */
    public void recordFailure(String email) {
        if (properties.isEnabled()) {
            counter.increment(ACCOUNT_PREFIX + email, properties.getAccountWindow());
        }
    }

    /**
     * Clears the account's failure count. Without this, a user who mistypes a few
     * times and then succeeds would stay near the threshold for the rest of the
     * window.
     */
    public void recordSuccess(String email) {
        if (properties.isEnabled()) {
            counter.clear(ACCOUNT_PREFIX + email);
        }
    }
}
