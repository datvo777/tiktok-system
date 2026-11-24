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

class CommentRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final IpRateLimiter counter = mock(IpRateLimiter.class);

    private CommentRateLimiter limiter(boolean enabled) {
        return new CommentRateLimiter(counter, enabled, 30, WINDOW);
    }

    @Test
    void anAccountOverItsLimitIsToldWhenToComeBack() {
        when(counter.increment("social:comment:account:a", WINDOW)).thenReturn(31L);

        assertThatThrownBy(() -> limiter(true).checkAllowed("a"))
                .isInstanceOfSatisfying(SocialExceptions.CommentRateLimited.class, e ->
                        assertThat(e.retryAfter()).isEqualTo(WINDOW));
    }

    @Test
    void anAccountAtItsLimitStillPasses() {
        when(counter.increment("social:comment:account:a", WINDOW)).thenReturn(30L);

        assertThatCode(() -> limiter(true).checkAllowed("a")).doesNotThrowAnyException();
    }

    @Test
    void aDisabledLimiterDoesNotCount() {
        assertThatCode(() -> limiter(false).checkAllowed("a")).doesNotThrowAnyException();

        verify(counter, never()).increment(any(), any());
    }

    @Test
    void aThrottledCommentOrReplyNeverReachesTheDatabaseOrTheOutbox() {
        var repository = mock(SocialRepository.class);
        var eligibility = mock(EligibilityDirectory.class);
        var outbox = mock(OutboxWriter.class);
        var rateLimiter = mock(CommentRateLimiter.class);
        var service = new SocialService(repository, eligibility, mock(AccountDirectory.class), outbox, rateLimiter);
        String account = UUID.randomUUID().toString();
        doThrow(new SocialExceptions.CommentRateLimited(WINDOW)).when(rateLimiter).checkAllowed(account);

        assertThatThrownBy(() -> service.comment(UUID.randomUUID().toString(), account, "hi"))
                .isInstanceOf(SocialExceptions.CommentRateLimited.class);
        assertThatThrownBy(() -> service.reply(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(), account, "hi"))
                .isInstanceOf(SocialExceptions.CommentRateLimited.class);

        verifyNoInteractions(repository, eligibility, outbox);
    }
}
