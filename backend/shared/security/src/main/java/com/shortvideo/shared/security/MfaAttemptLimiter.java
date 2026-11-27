package com.shortvideo.shared.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts and bounds attempts at the second step: per pending sign-in (so a stolen password buys a handful of
 * guesses, not a loop), per account and per client address. Also remembers which pending sign-ins have been
 * used up, so one cannot be replayed.
 *
 * <p><b>Fails closed</b> ({@link MfaUnavailableException}), the opposite of {@link IpRateLimiter}; see there
 * and in {@code docs/mfa-plan.md} for why the two steps differ.
 */
@Component
public class MfaAttemptLimiter {

    private static final String TOKEN_PREFIX = "mfa:tok:";
    private static final String USED_PREFIX = "mfa:used:";
    private static final String ACCOUNT_PREFIX = "mfa:acct:";
    private static final String IP_PREFIX = "mfa:ip:";
    private static final Duration UNAVAILABLE_RETRY = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final MfaProperties properties;

    public MfaAttemptLimiter(StringRedisTemplate redis, MfaProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Called before the code is looked at.
     *
     * @throws TooManyLoginAttemptsException with how long to wait, when the address or the account is over its limit
     * @throws MfaUnavailableException when the counters cannot be read
     */
    public void checkAllowed(String clientIp, String accountId) {
        long ipAttempts = increment(IP_PREFIX + clientIp, properties.getIpWindow());
        if (ipAttempts > properties.getMaxAttemptsPerIp()) {
            throw new TooManyLoginAttemptsException(properties.getIpWindow());
        }
        Long failures = read(ACCOUNT_PREFIX + accountId);
        if (failures != null && failures >= properties.getMaxFailuresPerAccount()) {
            throw new TooManyLoginAttemptsException(properties.getAccountWindow());
        }
    }

    /** Whether this pending sign-in has already been used, or has run out of attempts. */
    public boolean isSpent(String pendingTokenId) {
        return run(() -> Boolean.TRUE.equals(redis.hasKey(USED_PREFIX + pendingTokenId)));
    }

    /** A wrong code. @return true once this pending sign-in has no attempts left (and is now spent). */
    public boolean recordFailure(String pendingTokenId, String accountId) {
        long forAccount = increment(ACCOUNT_PREFIX + accountId, properties.getAccountWindow());
        long forToken = increment(TOKEN_PREFIX + pendingTokenId, properties.getPendingTtl());
        boolean exhausted = forToken >= properties.getMaxAttemptsPerToken();
        if (exhausted) {
            markSpent(pendingTokenId);
        }
        return exhausted || forAccount >= properties.getMaxFailuresPerAccount();
    }

    /** The code was right: the pending sign-in cannot be used again, and the account's failures are forgiven. */
    public void recordSuccess(String pendingTokenId, String accountId) {
        markSpent(pendingTokenId);
        run(() -> redis.delete(ACCOUNT_PREFIX + accountId));
    }

    private void markSpent(String pendingTokenId) {
        run(() -> {
            redis.opsForValue().set(USED_PREFIX + pendingTokenId, "1", properties.getPendingTtl().plusMinutes(1));
            return null;
        });
    }

    private long increment(String key, Duration window) {
        return run(() -> {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                throw new IllegalStateException("Redis returned no count");
            }
            if (count == 1L) {
                redis.expire(key, window);
            }
            return count;
        });
    }

    private Long read(String key) {
        return run(() -> {
            String value = redis.opsForValue().get(key);
            return value == null ? null : Long.parseLong(value);
        });
    }

    private <T> T run(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (TooManyLoginAttemptsException | MfaUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MfaUnavailableException(UNAVAILABLE_RETRY, e);
        }
    }
}
