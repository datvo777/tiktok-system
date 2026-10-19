package com.shortvideo.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * What the automated pre-screen does with a new upload, and who ends up looking at it.
 *
 * <p>The title and description reach the eligibility projection through a different consumer than
 * the upload event that triggers moderation, so the row may simply not be there yet. That has to
 * end in a human, not in a decision made on creator standing alone. And "pending" is not "queued":
 * a record nobody needs to see yet must stay out of the reviewers' queue, while anything uncertain
 * must go in at once.
 */
class ModerationServiceAutoApproveTest {

    private final ModerationJpaRepository repository = mock(ModerationJpaRepository.class);
    private final ModerationScreener screener = mock(ModerationScreener.class);
    private final EligibilityDirectory eligibility = mock(EligibilityDirectory.class);
    private final ModerationProperties properties = new ModerationProperties();
    private final ModerationService service = spy(new ModerationService(
            repository, null, null, null, screener, properties, eligibility, new SimpleMeterRegistry()));

    private final String videoId = UUID.randomUUID().toString();
    private final String creatorId = UUID.randomUUID().toString();

    private void givenNewRecord() {
        when(repository.existsById(any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        // approve() needs outbox and audit collaborators this test does not wire; what matters is that it is called.
        Mockito.doNothing().when(service).approve(anyString(), anyString());
    }

    private void givenMetadataAndVerdict(ScreeningVerdict verdict) {
        when(eligibility.findVideoEligibility(videoId)).thenReturn(Optional.of(mock(VideoEligibilityView.class)));
        when(screener.screen(any())).thenReturn(verdict);
    }

    private ModerationEntity savedRecord() {
        var saved = ArgumentCaptor.forClass(ModerationEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private void assertQueuedAs(QueueReason reason) {
        ModerationEntity record = savedRecord();
        assertThat(record.getState()).isEqualTo(ModerationState.PENDING);
        assertThat(record.getQueuedAt()).isNotNull();
        assertThat(record.getQueueReason()).isEqualTo(reason);
        verify(service, never()).approve(anyString(), anyString());
    }

    private void assertHeld() {
        ModerationEntity record = savedRecord();
        assertThat(record.getState()).isEqualTo(ModerationState.PENDING);
        assertThat(record.getQueuedAt()).isNull();
        assertThat(record.getQueueReason()).isNull();
        verify(service, never()).approve(anyString(), anyString());
    }

    @Test
    void unknownMetadataIsQueuedWithoutAskingTheScreener() {
        givenNewRecord();
        when(eligibility.findVideoEligibility(videoId)).thenReturn(Optional.empty());
        properties.setQueueMode(QueueMode.INTENT_GATED);

        service.createPending(videoId, creatorId);

        verify(screener, never()).screen(any());
        assertQueuedAs(QueueReason.UNSCREENED);
    }

    @Test
    void knownMetadataIsHandedToTheScreener() {
        givenNewRecord();
        givenMetadataAndVerdict(ScreeningVerdict.REFER_TO_HUMAN);

        service.createPending(videoId, creatorId);

        verify(screener).screen(any());
    }

    @Test
    void aScreenerThatThrowsIsQueuedNotPublished() {
        givenNewRecord();
        when(eligibility.findVideoEligibility(videoId)).thenReturn(Optional.of(mock(VideoEligibilityView.class)));
        when(screener.screen(any())).thenThrow(new IllegalStateException("boom"));
        properties.setQueueMode(QueueMode.INTENT_GATED);

        service.createPending(videoId, creatorId);

        assertQueuedAs(QueueReason.UNSCREENED);
    }

    @Test
    void withAutoApproveOffEveryUploadIsQueuedInEitherMode() {
        givenNewRecord();
        properties.setAutoApproveEnabled(false);
        properties.setQueueMode(QueueMode.INTENT_GATED);

        service.createPending(videoId, creatorId);

        verify(screener, never()).screen(any());
        assertQueuedAs(QueueReason.UPLOAD);
    }

    @Test
    void anAutoApprovedUploadIsApprovedAsTheSystemAndNotQueued() {
        givenNewRecord();
        givenMetadataAndVerdict(ScreeningVerdict.AUTO_APPROVE);
        properties.setQueueMode(QueueMode.INTENT_GATED);

        service.createPending(videoId, creatorId);

        assertThat(savedRecord().getQueuedAt()).isNull();
        verify(service).approve(videoId, ModerationService.SYSTEM_ACTOR);
    }

    @Test
    void aRiskyUploadIsQueuedAtOnceInEitherMode() {
        givenNewRecord();
        givenMetadataAndVerdict(ScreeningVerdict.REFER_TO_HUMAN);
        properties.setQueueMode(QueueMode.INTENT_GATED);

        service.createPending(videoId, creatorId);

        assertQueuedAs(QueueReason.RISK);
    }

    @Test
    void anUntrustedUploadIsQueuedWhenTheModeIsAll() {
        givenNewRecord();
        givenMetadataAndVerdict(ScreeningVerdict.HOLD_UNTIL_PUBLISH);
        properties.setQueueMode(QueueMode.ALL);

        service.createPending(videoId, creatorId);

        assertQueuedAs(QueueReason.UPLOAD);
    }

    @Test
    void anUntrustedUploadIsHeldWhenTheModeIsIntentGated() {
        givenNewRecord();
        givenMetadataAndVerdict(ScreeningVerdict.HOLD_UNTIL_PUBLISH);
        properties.setQueueMode(QueueMode.INTENT_GATED);

        service.createPending(videoId, creatorId);

        assertHeld();
    }

    @Test
    void requestingReviewOfAnUnknownVideoFailsSoTheEventIsRetried() {
        when(repository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() -> service.requestReview(videoId)).isInstanceOf(IllegalStateException.class);

        verify(repository, never()).enqueue(any(), any(), anyString());
    }

    @Test
    void requestingReviewQueuesAHeldVideoOnce() {
        when(repository.existsById(any())).thenReturn(true);
        when(repository.enqueue(any(), any(), eq("PUBLISH_REQUESTED"))).thenReturn(1, 0);

        assertThat(service.requestReview(videoId)).isTrue();
        // A redelivery (or a decided/queued record) matches no row and changes nothing.
        assertThat(service.requestReview(videoId)).isFalse();
    }
}
