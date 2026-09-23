package com.shortvideo.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rate limits account registration by IP.
 *
 * <p>Hashing the password with BCrypt costs ~100ms of CPU and runs before
 * {@code existsByEmail} is checked (see {@code AccountService.register}) — that
 * ordering is deliberate: it keeps the cost of probing a taken email the same
 * as probing a free one, since the response already discloses which is which
 * through its status code. Without a limiter here, that uniform cost is the
 * only thing standing between this endpoint and cheap bulk enumeration of
 * which emails already have accounts; this closes that gap directly instead
 * of relying on it.
 *
 * <p>Only a per-IP counter, unlike {@link LoginRateLimiter}'s two: registration
 * has no per-account "failure" to count — an attempt either creates the
 * account or 409s on a duplicate, and counting failed attempts against an
 * email that isn't registered yet would have nothing to key on.
 */
@Component
public class RegisterRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RegisterRateLimiter.class);
    private static final String IP_PREFIX = "register:ip:";

    private final IpRateLimiter counter;
    private final RegisterRateLimitProperties properties;

    public RegisterRateLimiter(IpRateLimiter counter, RegisterRateLimitProperties properties) {
        this.counter = counter;
        this.properties = properties;
    }

    /**
     * Called before the password is hashed, so a throttled attempt costs no
     * BCrypt at all.
     *
     * @throws TooManyRegistrationAttemptsException carrying how long to wait
     */
    public void checkAllowed(String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        long attempts = counter.increment(IP_PREFIX + clientIp, properties.getIpWindow());
        if (attempts > properties.getMaxAttemptsPerIp()) {
            log.warn("Throttled registration attempts from {}", clientIp);
            throw new TooManyRegistrationAttemptsException(properties.getIpWindow());
        }
    }
}
