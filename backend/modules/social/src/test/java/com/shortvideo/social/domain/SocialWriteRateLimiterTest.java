package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.shared.security.IpRateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SocialWriteRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final IpRateLimiter counter = mock(IpRateLimiter.class);

    private SocialWriteRateLimiter limiter(boolean enabled) {
        return new SocialWriteRateLimiter(counter, enabled, 1, 2, 3, 4, WINDOW);
    }

    @ParameterizedTest
    @CsvSource({"LIKE,like,1", "FOLLOW,follow,2", "SHARE,share,3", "VIEW,view,4"})
    void eachActionHasItsOwnCounterAndLimit(SocialWriteRateLimiter.Action action, String name, long max) {
        String key = "social:" + name + ":account:a";
        when(counter.increment(key, WINDOW)).thenReturn(max, max + 1);

        assertThatCode(() -> limiter(true).checkAllowed(action, "a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter(true).checkAllowed(action, "a"))
                .isInstanceOfSatisfying(SocialExceptions.WriteRateLimited.class, e ->
                        assertThat(e.retryAfter()).isEqualTo(WINDOW));
    }

    @Test
    void aDisabledLimiterDoesNotCount() {
        assertThatCode(() -> limiter(false).checkAllowed(SocialWriteRateLimiter.Action.LIKE, "a"))
                .doesNotThrowAnyException();

        verify(counter, never()).increment(any(), any());
    }

    @Test
    void aThrottledLikeFollowShareOrViewNeverReachesTheDatabaseOrTheOutbox() {
        var repository = mock(SocialRepository.class);
        var eligibility = mock(EligibilityDirectory.class);
        var accounts = mock(AccountDirectory.class);
        var outbox = mock(OutboxWriter.class);
        var rateLimiter = mock(SocialWriteRateLimiter.class);
        var service = new SocialService(
                repository, eligibility, accounts, outbox, mock(CommentRateLimiter.class), rateLimiter);
        String account = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        doThrow(new SocialExceptions.WriteRateLimited(WINDOW)).when(rateLimiter).checkAllowed(any(), any());

        assertThatThrownBy(() -> service.like(target, account))
                .isInstanceOf(SocialExceptions.WriteRateLimited.class);
        assertThatThrownBy(() -> service.follow(account, target))
                .isInstanceOf(SocialExceptions.WriteRateLimited.class);
        assertThatThrownBy(() -> service.share(target, account))
                .isInstanceOf(SocialExceptions.WriteRateLimited.class);
        assertThatThrownBy(() -> service.recordView(target, account, 3_000, false))
                .isInstanceOf(SocialExceptions.WriteRateLimited.class);

        verifyNoInteractions(repository, eligibility, accounts, outbox);
    }

    @Test
    void takingBackALikeOrAFollowIsNeverThrottled() {
        var rateLimiter = mock(SocialWriteRateLimiter.class);
        var eligibility = mock(EligibilityDirectory.class);
        var service = new SocialService(
                mock(SocialRepository.class), eligibility, mock(AccountDirectory.class), mock(OutboxWriter.class),
                mock(CommentRateLimiter.class), rateLimiter);

        service.unlike(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        service.unfollow(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        verifyNoInteractions(rateLimiter);
    }
}
