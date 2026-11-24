package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountState;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        SocialService service = new SocialService(null, null, accountDirectory, null, null);

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
        SocialService service = new SocialService(null, null, accountDirectory, null, null);
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
        SocialService service = new SocialService(repository, eligibilityDirectory, accountDirectory, outboxWriter, mock(CommentRateLimiter.class));

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
        SocialService service = new SocialService(repository, eligibilityDirectory, accountDirectory, outboxWriter, mock(CommentRateLimiter.class));

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

    // --- the like and comment totals the feed ranks on are fed by one delta event per change

    private final SocialRepository repository = mock(SocialRepository.class);
    private final EligibilityDirectory eligibility = mock(EligibilityDirectory.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final SocialService service = new SocialService(repository, eligibility, mock(AccountDirectory.class), outbox, mock(CommentRateLimiter.class));
    private final String videoId = UUID.randomUUID().toString();
    private final String ownerId = UUID.randomUUID().toString();
    private final String viewerId = UUID.randomUUID().toString();

    private void videoIsEligible() {
        when(eligibility.findVideoEligibility(videoId)).thenReturn(Optional.of(eligibleVideo(videoId, ownerId)));
    }

    /** What the service wrote to the outbox, as (eventType, payload) in order. */
    private List<EventEnvelope<?>> emitted() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<EventEnvelope<?>> captor = ArgumentCaptor.forClass((Class) EventEnvelope.class);
        verify(outbox, org.mockito.Mockito.atLeast(0)).append(captor.capture());
        return captor.getAllValues();
    }

    private static SocialEvents.EngagementChanged change(EventEnvelope<?> e) {
        return (SocialEvents.EngagementChanged) e.payload();
    }

    @Test
    void aNewLikeAddsOneToTheCountAndAnotherPersonsLikeAlsoNotifiesTheOwner() {
        videoIsEligible();
        when(repository.like(videoId, viewerId)).thenReturn(true);

        service.like(videoId, viewerId);

        List<EventEnvelope<?>> events = emitted();
        assertThat(events).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED, EventTypes.SOCIAL_VIDEO_LIKED);
        assertThat(change(events.get(0))).isEqualTo(new SocialEvents.EngagementChanged(videoId, 1, 0));
    }

    @Test
    void likingYourOwnVideoStillCountsEvenThoughItNotifiesNobody() {
        videoIsEligible();
        when(repository.like(videoId, ownerId)).thenReturn(true);

        service.like(videoId, ownerId);

        assertThat(emitted()).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED);
    }

    @Test
    void aRepeatedLikeChangesNothing() {
        videoIsEligible();
        when(repository.like(videoId, viewerId)).thenReturn(false);

        service.like(videoId, viewerId);

        verify(outbox, never()).append(any());
    }

    @Test
    void anUnlikeTakesOneOffButOnlyWhenALikeWasRemoved() {
        when(repository.unlike(videoId, viewerId)).thenReturn(true, false);

        service.unlike(videoId, viewerId);
        service.unlike(videoId, viewerId); // repeated: nothing was removed

        List<EventEnvelope<?>> events = emitted();
        assertThat(events).hasSize(1);
        assertThat(change(events.get(0))).isEqualTo(new SocialEvents.EngagementChanged(videoId, -1, 0));
    }

    @Test
    void aCommentAndAReplyEachAddOneToTheCommentCount() {
        videoIsEligible();
        String parentId = UUID.randomUUID().toString();
        when(repository.topLevelCommentAuthor(parentId, videoId)).thenReturn(Optional.of(ownerId));
        when(repository.addComment(eq(videoId), eq(viewerId), anyString(), any(), any()))
                .thenAnswer(i -> new CommentView(
                        UUID.randomUUID().toString(), videoId, viewerId, i.getArgument(2), Instant.now(), null, 0, List.of()));

        service.comment(videoId, viewerId, "hi");
        service.reply(videoId, parentId, viewerId, "hello");

        assertThat(emitted()).filteredOn(e -> e.eventType().equals(EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED))
                .extracting(e -> change(e))
                .containsExactly(
                        new SocialEvents.EngagementChanged(videoId, 0, 1),
                        new SocialEvents.EngagementChanged(videoId, 0, 1));
    }

    @Test
    void removingATopLevelCommentTakesOffTheCommentAndTheRepliesThatWentWithIt() {
        videoIsEligible();
        String commentId = UUID.randomUUID().toString();
        when(repository.findForDelete(commentId))
                .thenReturn(Optional.of(new SocialRepository.CommentOwnership(commentId, videoId, viewerId, null)));
        when(repository.softDeleteComment(commentId, viewerId, true)).thenReturn(4); // itself and three replies

        service.deleteComment(videoId, commentId, viewerId);

        List<EventEnvelope<?>> events = emitted();
        assertThat(events).hasSize(1);
        assertThat(change(events.get(0))).isEqualTo(new SocialEvents.EngagementChanged(videoId, 0, -4));
    }

    @Test
    void removingACommentThatWasAlreadyHiddenChangesNothing() {
        videoIsEligible();
        String commentId = UUID.randomUUID().toString();
        when(repository.findForDelete(commentId))
                .thenReturn(Optional.of(new SocialRepository.CommentOwnership(commentId, videoId, viewerId, null)));
        when(repository.softDeleteComment(commentId, viewerId, true)).thenReturn(0);

        service.deleteComment(videoId, commentId, viewerId);

        verify(outbox, never()).append(any());
    }
}
