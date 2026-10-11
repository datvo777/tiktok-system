package com.shortvideo.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.security.IpRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamOpenRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final IpRateLimiter counter = mock(IpRateLimiter.class);
    private final RealtimeProperties properties = new RealtimeProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private StreamOpenRateLimiter limiter(int max) {
        properties.setMaxOpensPerWindow(max);
        properties.setOpenWindow(WINDOW);
        return new StreamOpenRateLimiter(counter, properties, meters);
    }

    @Test
    void anAccountWithinTheLimitMayOpen() {
        when(counter.increment("realtime:open:account:a", WINDOW)).thenReturn(30L);

        assertThat(limiter(30).check("a")).isEmpty();
    }

    @Test
    void anAccountOverTheLimitIsToldHowLongToWait() {
        when(counter.increment("realtime:open:account:a", WINDOW)).thenReturn(31L);

        assertThat(limiter(30).check("a")).contains(WINDOW);
        assertThat(meters.get("realtime.throttled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void oneAccountBeingThrottledDoesNotAffectAnother() {
        when(counter.increment("realtime:open:account:a", WINDOW)).thenReturn(99L);
        when(counter.increment("realtime:open:account:b", WINDOW)).thenReturn(1L);

        StreamOpenRateLimiter limiter = limiter(30);

        assertThat(limiter.check("a")).isPresent();
        assertThat(limiter.check("b")).isEmpty();
    }

    @Test
    void zeroTurnsTheLimitOff() {
        assertThat(limiter(0).check("a")).isEmpty();

        verify(counter, never()).increment(any(), any());
    }
}
