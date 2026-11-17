package com.shortvideo.upload.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class UploadCreateRateLimiterTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final IpRateLimiter counter = mock(IpRateLimiter.class);

    private UploadCreateRateLimiter limiter(int globalMax) {
        return new UploadCreateRateLimiter(counter, true, 30, HOUR, globalMax, MINUTE);
    }

    @Test
    void anAccountOverItsOwnLimitIsToldItIsRateLimited() {
        when(counter.increment(eq("upload:create:account:a"), any())).thenReturn(31L);

        assertThatThrownBy(() -> limiter(600).checkAllowed("a"))
                .isInstanceOf(UploadExceptions.UploadRateLimited.class);
    }

    @Test
    void anAccountThatIsThrottledDoesNotSpendTheSharedBudget() {
        when(counter.increment(eq("upload:create:account:a"), any())).thenReturn(31L);

        assertThatThrownBy(() -> limiter(600).checkAllowed("a")).isInstanceOf(UploadExceptions.UploadRateLimited.class);

        verify(counter, never()).increment(eq("upload:create:global"), any());
    }

    @Test
    void everyoneIsTurnedAwayOnceTheGlobalCapIsReached() {
        when(counter.increment(eq("upload:create:account:a"), any())).thenReturn(1L);
        when(counter.increment("upload:create:global", MINUTE)).thenReturn(601L);

        assertThatThrownBy(() -> limiter(600).checkAllowed("a"))
                .isInstanceOfSatisfying(UploadExceptions.UploadCapacityExceeded.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.retryAfter()).isEqualTo(MINUTE));
    }

    @Test
    void requestsUnderBothLimitsPass() {
        when(counter.increment(eq("upload:create:account:a"), any())).thenReturn(1L);
        when(counter.increment("upload:create:global", MINUTE)).thenReturn(600L);

        assertThatCode(() -> limiter(600).checkAllowed("a")).doesNotThrowAnyException();
    }

    @Test
    void zeroTurnsTheGlobalCapOff() {
        when(counter.increment(eq("upload:create:account:a"), any())).thenReturn(1L);

        assertThatCode(() -> limiter(0).checkAllowed("a")).doesNotThrowAnyException();

        verify(counter, never()).increment(eq("upload:create:global"), any());
    }
}
