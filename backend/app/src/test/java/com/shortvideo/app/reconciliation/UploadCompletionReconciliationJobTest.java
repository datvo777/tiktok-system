package com.shortvideo.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.moderation.api.ModerationBackfill;
import com.shortvideo.moderation.api.ModerationDecisionView;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationBackfill;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.publication.api.PublicationStateView;
import com.shortvideo.upload.api.UploadDirectory;
import com.shortvideo.upload.api.UploadDirectory.CompletedUploadView;
import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.DurabilityState;
import com.shortvideo.video.api.LegalServingState;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import com.shortvideo.video.api.VideoProcessingDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadCompletionReconciliationJobTest {

    private static final CompletedUploadView UPLOAD =
            new CompletedUploadView("u1", "v1", "a1", "sources/v1/original", Instant.now());

    private final UploadDirectory uploads = mock(UploadDirectory.class);
    private final VideoPlaybackDirectory videos = mock(VideoPlaybackDirectory.class);
    private final VideoProcessingDispatcher dispatcher = mock(VideoProcessingDispatcher.class);
    private final ModerationDirectory moderationDirectory = mock(ModerationDirectory.class);
    private final ModerationBackfill moderationBackfill = mock(ModerationBackfill.class);
    private final PublicationDirectory publicationDirectory = mock(PublicationDirectory.class);
    private final PublicationBackfill publicationBackfill = mock(PublicationBackfill.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private UploadCompletionReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new UploadCompletionReconciliationJob(
                uploads,
                videos,
                dispatcher,
                moderationDirectory,
                moderationBackfill,
                publicationDirectory,
                publicationBackfill,
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                meters);
        when(uploads.completedBetween(any(), any(), anyString(), anyInt())).thenReturn(List.of(UPLOAD));
        when(moderationDirectory.findDecision("v1")).thenReturn(Optional.empty());
        when(publicationDirectory.findState("v1")).thenReturn(Optional.empty());
    }

    private void video(ProcessingState state, AssetLifecycleState lifecycle) {
        when(videos.findForPlayback("v1"))
                .thenReturn(Optional.of(new VideoPlaybackView(
                        "v1", "a1", state, 1, DurabilityState.DURABLE, lifecycle, LegalServingState.CLEAR, 1)));
    }

    @Test
    void repairsAllThreeWhenTheWholeFanOutWasLost() {
        video(ProcessingState.CREATED, AssetLifecycleState.ACTIVE);
        when(publicationBackfill.backfillDraft(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(moderationBackfill.createPending("v1", "a1")).thenReturn(true);
        when(dispatcher.dispatchProcessing("v1", "sources/v1/original")).thenReturn(true);

        job.reconcile();

        verify(publicationBackfill).backfillDraft("v1", "a1", false);
        verify(moderationBackfill).createPending("v1", "a1");
        verify(dispatcher).dispatchProcessing("v1", "sources/v1/original");
        assertThat(meters.counter("upload.completion.repaired").count()).isEqualTo(3);
    }

    @Test
    void marksALateDraftReadyWhenTheVideoAlreadyFinishedProcessing() {
        video(ProcessingState.READY, AssetLifecycleState.ACTIVE);

        job.reconcile();

        // The READY event that would have said so was lost with the draft.
        verify(publicationBackfill).backfillDraft("v1", "a1", true);
        verify(dispatcher, never()).dispatchProcessing(anyString(), anyString());
    }

    @Test
    void leavesAHealthyUploadAlone() {
        video(ProcessingState.READY, AssetLifecycleState.ACTIVE);
        when(moderationDirectory.findDecision("v1")).thenReturn(Optional.of(mock(ModerationDecisionView.class)));
        when(publicationDirectory.findState("v1")).thenReturn(Optional.of(mock(PublicationStateView.class)));

        job.reconcile();

        verify(publicationBackfill, never()).backfillDraft(anyString(), anyString(), anyBoolean());
        verify(moderationBackfill, never()).createPending(anyString(), anyString());
        verify(dispatcher, never()).dispatchProcessing(anyString(), anyString());
        assertThat(meters.counter("upload.completion.repaired").count()).isZero();
    }

    @Test
    void doesNotResurrectRecordsForARemovedVideo() {
        video(ProcessingState.READY, AssetLifecycleState.DELETED);

        job.reconcile();

        verify(publicationBackfill, never()).backfillDraft(anyString(), anyString(), anyBoolean());
        verify(moderationBackfill, never()).createPending(anyString(), anyString());
    }

    @Test
    void oneFailingUploadDoesNotStopTheRest() {
        var second = new CompletedUploadView("u2", "v2", "a2", "sources/v2/original", Instant.now());
        when(uploads.completedBetween(any(), any(), anyString(), anyInt())).thenReturn(List.of(UPLOAD, second));
        when(videos.findForPlayback("v1")).thenThrow(new IllegalStateException("db down"));
        when(videos.findForPlayback("v2"))
                .thenReturn(Optional.of(new VideoPlaybackView(
                        "v2", "a2", ProcessingState.CREATED, 1, DurabilityState.DURABLE,
                        AssetLifecycleState.ACTIVE, LegalServingState.CLEAR, 1)));
        when(publicationDirectory.findState("v2")).thenReturn(Optional.empty());
        when(moderationDirectory.findDecision("v2")).thenReturn(Optional.empty());

        job.reconcile();

        assertThat(meters.counter("upload.completion.repair_failed").count()).isEqualTo(1);
        verify(dispatcher).dispatchProcessing(eq("v2"), anyString());
    }
}
