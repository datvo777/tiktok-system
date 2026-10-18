package com.shortvideo.moderation.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the automated pre-screen does when it cannot see the video's text.
 *
 * <p>The title and description reach the eligibility projection through a
 * different consumer than the upload event that triggers moderation, so the row
 * may simply not be there yet. That has to end in a human, not in a decision made
 * on creator standing alone.
 */
class ModerationServiceAutoApproveTest {

    private final ModerationJpaRepository repository = mock(ModerationJpaRepository.class);
    private final ModerationScreener screener = mock(ModerationScreener.class);
    private final EligibilityDirectory eligibility = mock(EligibilityDirectory.class);
    private final ModerationService service = new ModerationService(
            repository, null, null, null, screener, new ModerationProperties(), eligibility);

    private final String videoId = UUID.randomUUID().toString();
    private final String creatorId = UUID.randomUUID().toString();

    private void givenNewRecord() {
        when(repository.existsById(any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void unknownMetadataIsReferredWithoutAskingTheScreener() {
        givenNewRecord();
        when(eligibility.findVideoEligibility(videoId)).thenReturn(Optional.empty());

        service.createPending(videoId, creatorId);

        verify(screener, never()).screen(any());
        // Still PENDING: createPending saved it once and nothing approved it.
        verify(repository).saveAndFlush(any());
    }

    @Test
    void knownMetadataIsHandedToTheScreener() {
        givenNewRecord();
        var view = mock(VideoEligibilityView.class);
        when(eligibility.findVideoEligibility(videoId)).thenReturn(Optional.of(view));
        when(screener.screen(any())).thenReturn(ScreeningVerdict.REFER_TO_HUMAN);

        service.createPending(videoId, creatorId);

        verify(screener).screen(any());
    }
}
