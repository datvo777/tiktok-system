package com.shortvideo.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.moderation.api.ModerationBackfill;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.publication.api.PublicationStateView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeldReviewReconciliationJobTest {

    private final ModerationDirectory moderationDirectory = mock(ModerationDirectory.class);
    private final ModerationBackfill moderationBackfill = mock(ModerationBackfill.class);
    private final PublicationDirectory publicationDirectory = mock(PublicationDirectory.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private HeldReviewReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new HeldReviewReconciliationJob(moderationDirectory, moderationBackfill, publicationDirectory, meters);
        when(moderationDirectory.findHeldPage(any(), anyInt())).thenReturn(List.of("v1"));
    }

    private void publication(boolean intent) {
        when(publicationDirectory.findState("v1"))
                .thenReturn(Optional.of(new PublicationStateView("v1", "a1", "PUBLISH_PENDING", intent, 2)));
    }

    @Test
    void aHeldVideoWhoseOwnerAskedToPublishIsQueued() {
        publication(true);
        when(moderationBackfill.requestReview("v1")).thenReturn(true);

        job.reconcile();

        verify(moderationBackfill).requestReview("v1");
        assertThat(meters.counter("moderation.review.repaired").count()).isEqualTo(1.0);
    }

    @Test
    void aHeldVideoNobodyHasAskedToPublishStaysHeld() {
        publication(false);

        job.reconcile();

        verify(moderationBackfill, never()).requestReview(any());
        assertThat(meters.counter("moderation.review.repaired").count()).isZero();
    }

    @Test
    void aVideoWithNoDraftYetIsLeftAlone() {
        when(publicationDirectory.findState("v1")).thenReturn(Optional.empty());

        job.reconcile();

        verify(moderationBackfill, never()).requestReview(any());
    }

    @Test
    void aVideoQueuedInTheMeantimeIsNotCountedAsARepair() {
        publication(true);
        when(moderationBackfill.requestReview("v1")).thenReturn(false);

        job.reconcile();

        assertThat(meters.counter("moderation.review.repaired").count()).isZero();
    }

    @Test
    void oneFailingVideoDoesNotStopTheSweep() {
        when(moderationDirectory.findHeldPage(any(), anyInt())).thenReturn(List.of("v1", "v2"));
        when(publicationDirectory.findState("v1")).thenThrow(new IllegalStateException("db down"));
        when(publicationDirectory.findState("v2"))
                .thenReturn(Optional.of(new PublicationStateView("v2", "a1", "PUBLISH_PENDING", true, 2)));
        when(moderationBackfill.requestReview("v2")).thenReturn(true);

        job.reconcile();

        verify(moderationBackfill).requestReview(eq("v2"));
        assertThat(meters.counter("moderation.review.repair_failed").count()).isEqualTo(1.0);
    }
}
