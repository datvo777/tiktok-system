package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MfaAttemptLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private Map<String, Long> counts;
    private Map<String, String> values;
    private MfaProperties properties;
    private MfaAttemptLimiter limiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        counts = new HashMap<>();
        values = new HashMap<>();
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(i -> counts.merge(i.getArgument(0), 1L, Long::sum));
        when(ops.get(anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            return counts.containsKey(key) ? String.valueOf(counts.get(key)) : values.get(key);
        });
        when(redis.hasKey(anyString())).thenAnswer(i -> values.containsKey(i.<String>getArgument(0)));
        properties = new MfaProperties();
        properties.setMaxAttemptsPerToken(3);
        properties.setMaxFailuresPerAccount(5);
        properties.setMaxAttemptsPerIp(4);
        limiter = new MfaAttemptLimiter(redis, properties);
    }

    @Test
    void aPendingSignInIsSpentAfterItsAttemptsRunOut() {
        assertThat(limiter.recordFailure("tok", "acct")).isFalse();
        assertThat(limiter.recordFailure("tok", "acct")).isFalse();
        assertThat(limiter.isSpent("tok")).isFalse();

        assertThat(limiter.recordFailure("tok", "acct")).isTrue();
        // markSpent wrote through the mock's set(); mirror it for hasKey.
        org.mockito.Mockito.verify(ops).set(org.mockito.ArgumentMatchers.eq("mfa:used:tok"), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    void anotherPendingSignInForTheSameAccountIsNotSpent() {
        limiter.recordFailure("tok1", "acct");
        limiter.recordFailure("tok1", "acct");
        limiter.recordFailure("tok1", "acct");

        assertThat(limiter.isSpent("tok2")).isFalse();
    }

    @Test
    void anAccountOverItsFailureLimitIsRefused() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("tok" + i, "acct");
        }

        assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4", "acct")).isInstanceOf(TooManyLoginAttemptsException.class);
        limiter.checkAllowed("1.2.3.4", "someone-else");
    }

    @Test
    void anAddressOverItsLimitIsRefusedWhoeverItAsksAbout() {
        for (int i = 0; i < 4; i++) {
            limiter.checkAllowed("1.2.3.4", "acct" + i);
        }

        assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4", "acct9")).isInstanceOf(TooManyLoginAttemptsException.class);
        limiter.checkAllowed("5.6.7.8", "acct9");
    }

    @Test
    void failsClosedWhenRedisCannotCount() {
        when(ops.increment(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4", "acct")).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> limiter.recordFailure("tok", "acct")).isInstanceOf(MfaUnavailableException.class);
    }

    @Test
    void failsClosedWhenRedisCannotAnswerWhetherATokenIsSpent() {
        when(redis.hasKey(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        assertThatThrownBy(() -> limiter.isSpent("tok")).isInstanceOf(MfaUnavailableException.class);
    }
}
