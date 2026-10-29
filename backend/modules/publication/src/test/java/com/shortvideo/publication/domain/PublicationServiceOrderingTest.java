package com.shortvideo.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.shared.outbox.OutboxWriter;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Auto-approval emits {@code video.moderation.approved} straight after the
 * upload event, and the draft is created by a different consumer of the same
 * topic, so the approval can be handled before the draft exists.
 *
 * <p>That has to be a retryable failure, not a silently dropped approval: the
 * flag is what lets the video publish, and nothing re-sends the event once the
 * listener has acknowledged it.
 */
class PublicationServiceOrderingTest {

    private final PublicationJpaRepository repository = mock(PublicationJpaRepository.class);
    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final PublicationService service = new PublicationService(repository, outboxWriter, mock(PublishToggleRateLimiter.class));

    private final String videoId = UUID.randomUUID().toString();

    @Test
    void approvalBeforeTheDraftFailsSoTheListenerRetriesIt() {
        when(repository.findById(UUID.fromString(videoId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onModerationApproved(videoId, 1))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void approvalOnceTheDraftExistsIsRecorded() {
        var draft = new PublicationEntity(UUID.fromString(videoId), UUID.randomUUID());
        when(repository.findById(UUID.fromString(videoId))).thenReturn(Optional.of(draft));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onModerationApproved(videoId, 1);

        assertThat(draft.isModerationApproved()).isTrue();
    }

    private PublicationEntity draftWithRejection() {
        var draft = new PublicationEntity(UUID.fromString(videoId), UUID.randomUUID());
        when(repository.findById(UUID.fromString(videoId))).thenReturn(Optional.of(draft));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        draft.requestPublish();
        draft.setProcessingReady(true);
        service.onModerationApproved(videoId, 1);
        service.onModerationRejected(videoId, 2);
        return draft;
    }

    /** The case that mattered: approved and rejected do not commute, so arrival order must not decide. */
    @Test
    void anApprovalOlderThanTheRejectionDoesNotBringARejectedVideoBack() {
        var draft = draftWithRejection();

        service.onModerationApproved(videoId, 1);

        assertThat(draft.getState()).isEqualTo(PublicationState.SUSPENDED);
        assertThat(draft.isModerationApproved()).isFalse();
    }

    @Test
    void aRejectionOlderThanTheReinstatementDoesNotSuspendAgain() {
        var draft = draftWithRejection();
        service.onModerationReinstated(videoId, 3);
        assertThat(draft.getState()).isEqualTo(PublicationState.PUBLISHED);

        service.onModerationRejected(videoId, 2);

        assertThat(draft.getState()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void aStaleDecisionAppendsNoEvent() {
        draftWithRejection();
        org.mockito.Mockito.clearInvocations(outboxWriter);

        service.onModerationApproved(videoId, 1);

        verify(outboxWriter, never()).append(any());
    }
}
