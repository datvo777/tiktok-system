package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.outbox.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Existence non-disclosure: a suspended (or otherwise ineligible) account must
 * answer exactly like an account id that was never registered at all, both for
 * a profile lookup and for a follow attempt — reporting the real state is the
 * one place this used to leak who had been actioned.
 */
class SocialServiceTest {

    private static AccountView suspendedAccount(String accountId) {
        return new AccountView(accountId, "Some Name", "handle", null, AccountState.SUSPENDED, 1L, Instant.now());
    }

    @Test
    void profileForSuspendedAndUnknownAccountProduceTheIdenticalNotFoundResponse() {
        AccountDirectory accountDirectory = mock(AccountDirectory.class);
        SocialService service = new SocialService(null, null, accountDirectory, null);

        String suspendedId = UUID.randomUUID().toString();
        when(accountDirectory.find(suspendedId)).thenReturn(Optional.of(suspendedAccount(suspendedId)));

        String unknownId = UUID.randomUUID().toString();
        when(accountDirectory.find(unknownId)).thenReturn(Optional.empty());

        Throwable suspended = catchThrowable(() -> service.profile(suspendedId, null));
        Throwable unknown = catchThrowable(() -> service.profile(unknownId, null));

        assertThat(suspended).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(unknown).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(suspended.getMessage()).isEqualTo(unknown.getMessage());
    }

    @Test
    void followOfSuspendedAndUnknownAccountProduceTheIdenticalNotFoundResponse() {
        AccountDirectory accountDirectory = mock(AccountDirectory.class);
        SocialService service = new SocialService(null, null, accountDirectory, null);
        String followerId = UUID.randomUUID().toString();

        String suspendedId = UUID.randomUUID().toString();
        when(accountDirectory.find(suspendedId)).thenReturn(Optional.of(suspendedAccount(suspendedId)));

        String unknownId = UUID.randomUUID().toString();
        when(accountDirectory.find(unknownId)).thenReturn(Optional.empty());

        Throwable suspended = catchThrowable(() -> service.follow(followerId, suspendedId));
        Throwable unknown = catchThrowable(() -> service.follow(followerId, unknownId));

        assertThat(suspended).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(unknown).isInstanceOf(SocialExceptions.CreatorNotFound.class);
        assertThat(suspended.getMessage()).isEqualTo(unknown.getMessage());
    }

    private static VideoEligibilityView eligibleVideo(String videoId, String creatorId) {
        return new VideoEligibilityView(
                videoId, creatorId, "title", "description", "READY", 1, "DURABLE", "APPROVED", "PUBLISHED", false,
                "READY", "CLEARED", true, 1, 1, 1, 1, Instant.now());
    }

    /**
     * Mentions are resolved to account ids when the comment is written, not
     * re-parsed from the stored text later — that is what keeps an old mention
     * pointing at the same person after they rename or lose the handle.
     */
    @Test
    void commentResolvesAtHandleMentionsToAccountIdsAtWriteTime() {
        SocialRepository repository = mock(SocialRepository.class);
        EligibilityDirectory eligibilityDirectory = mock(EligibilityDirectory.class);
        AccountDirectory accountDirectory = mock(AccountDirectory.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        SocialService service = new SocialService(repository, eligibilityDirectory, accountDirectory, outboxWriter);

        String videoId = UUID.randomUUID().toString();
        String creatorId = UUID.randomUUID().toString();
        String commenterId = UUID.randomUUID().toString();
        String mentionedId = UUID.randomUUID().toString();

        when(eligibilityDirectory.findVideoEligibility(videoId))
                .thenReturn(Optional.of(eligibleVideo(videoId, creatorId)));
        when(accountDirectory.findAllByHandle(Set.of("dat")))
                .thenReturn(Map.of(
                        "dat", new AccountView(mentionedId, "Dat", "dat", null, AccountState.ACTIVE, 1L, Instant.now())));
        when(repository.addComment(eq(videoId), eq(commenterId), anyString(), isNull(), any()))
                .thenAnswer(invocation -> new CommentView(
                        UUID.randomUUID().toString(),
                        videoId,
                        commenterId,
                        invocation.getArgument(2),
                        Instant.now(),
                        null,
                        0,
                        List.copyOf(invocation.getArgument(4))));

        CommentView comment = service.comment(videoId, commenterId, "cam on @dat nhe!");

        assertThat(comment.mentions()).containsExactly(new CommentMention("dat", mentionedId));
    }

    /** A mention that resolves to nobody is dropped rather than failing the comment. */
    @Test
    void commentWithAnUnknownHandleIsPostedWithNoMention() {
        SocialRepository repository = mock(SocialRepository.class);
        EligibilityDirectory eligibilityDirectory = mock(EligibilityDirectory.class);
        AccountDirectory accountDirectory = mock(AccountDirectory.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        SocialService service = new SocialService(repository, eligibilityDirectory, accountDirectory, outboxWriter);

        String videoId = UUID.randomUUID().toString();
        String creatorId = UUID.randomUUID().toString();
        String commenterId = UUID.randomUUID().toString();

        when(eligibilityDirectory.findVideoEligibility(videoId))
                .thenReturn(Optional.of(eligibleVideo(videoId, creatorId)));
        when(accountDirectory.findAllByHandle(Set.of("nobody"))).thenReturn(Map.of());
        when(repository.addComment(eq(videoId), eq(commenterId), anyString(), isNull(), any()))
                .thenAnswer(invocation -> new CommentView(
                        UUID.randomUUID().toString(),
                        videoId,
                        commenterId,
                        invocation.getArgument(2),
                        Instant.now(),
                        null,
                        0,
                        List.copyOf(invocation.getArgument(4))));

        CommentView comment = service.comment(videoId, commenterId, "hey @nobody");

        assertThat(comment.mentions()).isEmpty();
    }
}
