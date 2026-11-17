package com.shortvideo.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.moderation.api.ModerationDecisionView;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationBackfill;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.DurabilityState;
import com.shortvideo.video.api.LegalServingState;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicationFlagReconciliationJobTest {

    private final PublicationDirectory publicationDirectory = mock(PublicationDirectory.class);
    private final PublicationBackfill publicationBackfill = mock(PublicationBackfill.class);
    private final ModerationDirectory moderationDirectory = mock(ModerationDirectory.class);
    private final VideoPlaybackDirectory videoDirectory = mock(VideoPlaybackDirectory.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private PublicationFlagReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new PublicationFlagReconciliationJob(
                publicationDirectory, publicationBackfill, moderationDirectory, videoDirectory, meters);
        when(publicationDirectory.findUnsettledPage(any(), anyInt())).thenReturn(List.of("v1"));
    }

    private void video(ProcessingState state) {
        when(videoDirectory.findForPlayback("v1"))
                .thenReturn(Optional.of(new VideoPlaybackView(
                        "v1", "a1", state, 1, DurabilityState.DURABLE, AssetLifecycleState.ACTIVE,
                        LegalServingState.values()[0], 3)));
    }

    private void moderation(String state, long version) {
        when(moderationDirectory.findDecision("v1")).thenReturn(Optional.of(new ModerationDecisionView("v1", "a1", state, version)));
    }

    @Test
    void passesWhatTheSourcesSayNowAndCountsARepair() {
        video(ProcessingState.READY);
        moderation("APPROVED", 1);
        when(publicationBackfill.reconcileFlags("v1", true, "APPROVED", 1)).thenReturn(true);

        job.reconcile();

        verify(publicationBackfill).reconcileFlags("v1", true, "APPROVED", 1);
        assertThat(meters.counter("publication.flags.repaired").count()).isEqualTo(1.0);
    }

    @Test
    void aVideoNotReadyAndNotYetDecidedIsReportedAsSuch() {
        video(ProcessingState.TRANSCODING);
        when(moderationDirectory.findDecision("v1")).thenReturn(Optional.empty());

        job.reconcile();

        verify(publicationBackfill).reconcileFlags(eq("v1"), eq(false), eq(null), anyLong());
        assertThat(meters.counter("publication.flags.repaired").count()).isZero();
    }

    @Test
    void oneFailingVideoDoesNotStopTheSweep() {
        when(publicationDirectory.findUnsettledPage(any(), anyInt())).thenReturn(List.of("v1", "v2"));
        when(videoDirectory.findForPlayback("v1")).thenThrow(new IllegalStateException("db down"));
        when(videoDirectory.findForPlayback("v2")).thenReturn(Optional.empty());
        when(moderationDirectory.findDecision(any())).thenReturn(Optional.empty());

        job.reconcile();

        verify(publicationBackfill).reconcileFlags(eq("v2"), anyBoolean(), any(), anyLong());
        assertThat(meters.counter("publication.flags.repair_failed").count()).isEqualTo(1.0);
    }
}
