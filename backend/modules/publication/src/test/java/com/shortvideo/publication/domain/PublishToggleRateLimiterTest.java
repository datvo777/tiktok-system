package com.shortvideo.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishToggleRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final IpRateLimiter counter = mock(IpRateLimiter.class);

    private PublishToggleRateLimiter limiter(boolean enabled) {
        return new PublishToggleRateLimiter(counter, enabled, 20, WINDOW);
    }

    @Test
    void anAccountOverItsLimitIsToldWhenToComeBack() {
        when(counter.increment("publication:toggle:account:a", WINDOW)).thenReturn(21L);

        assertThatThrownBy(() -> limiter(true).checkAllowed("a"))
                .isInstanceOfSatisfying(PublicationExceptions.PublishRateLimited.class, e ->
                        assertThat(e.retryAfter()).isEqualTo(WINDOW));
    }

    @Test
    void anAccountAtItsLimitStillPasses() {
        when(counter.increment("publication:toggle:account:a", WINDOW)).thenReturn(20L);

        assertThatCode(() -> limiter(true).checkAllowed("a")).doesNotThrowAnyException();
    }

    @Test
    void aDisabledLimiterDoesNotCount() {
        assertThatCode(() -> limiter(false).checkAllowed("a")).doesNotThrowAnyException();

        verify(counter, never()).increment(any(), any());
    }

    @Test
    void aThrottledCallNeverReachesTheDatabase() {
        var repository = mock(PublicationJpaRepository.class);
        var rateLimiter = mock(PublishToggleRateLimiter.class);
        var service = new PublicationService(repository, mock(OutboxWriter.class), rateLimiter);
        String owner = UUID.randomUUID().toString();
        doThrow(new PublicationExceptions.PublishRateLimited(WINDOW)).when(rateLimiter).checkAllowed(owner);

        assertThatThrownBy(() -> service.requestPublish(UUID.randomUUID().toString(), owner))
                .isInstanceOf(PublicationExceptions.PublishRateLimited.class);
        assertThatThrownBy(() -> service.withdrawPublish(UUID.randomUUID().toString(), owner))
                .isInstanceOf(PublicationExceptions.PublishRateLimited.class);

        verify(repository, never()).findById(any());
    }
}
