package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.video.api.ProcessingState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FailedSourceCleanupJobTest {

    private final VideoJpaRepository videos = mock(VideoJpaRepository.class);
    private final SupersededAssetJpaRepository queue = mock(SupersededAssetJpaRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FailedSourceCleanupJob job = new FailedSourceCleanupJob(
            videos,
            queue,
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            Duration.ofDays(7),
            meters);

    private VideoEntity failed(String failureClass) {
        VideoEntity video = new VideoEntity(UUID.randomUUID(), UUID.randomUUID(), "t", "d");
        video.dispatchProcessing("sources/" + video.getVideoId() + "/original");
        video.markFailed(failureClass);
        return video;
    }

    private void due(VideoEntity... due) {
        when(videos.findFailedAwaitingSourcePurge(eq(ProcessingState.FAILED), eq("TERMINAL"), any(), any(Pageable.class)))
                .thenReturn(List.of(due));
        for (VideoEntity video : due) {
            when(videos.findForUpdate(video.getVideoId())).thenReturn(Optional.of(video));
        }
    }

    @Test
    void aTerminallyFailedVideoGetsItsSourceScheduledForPurge() {
        VideoEntity video = failed("TERMINAL");
        due(video);

        job.sweep();

        ArgumentCaptor<SupersededAssetEntity> row = ArgumentCaptor.forClass(SupersededAssetEntity.class);
        verify(queue).saveAndFlush(row.capture());
        assertThat(row.getValue().prefix()).isEqualTo("sources/" + video.getVideoId() + "/");
        assertThat(meters.get("video.failed_source.scheduled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aVideoReprocessedSinceItWasSelectedIsLeftAlone() {
        VideoEntity selected = failed("TERMINAL");
        due(selected);
        // Under the lock it is no longer failed: an admin retried it in the meantime.
        VideoEntity retried = new VideoEntity(selected.getVideoId(), selected.getOwnerAccountId(), "t", "d");
        retried.dispatchProcessing("sources/" + selected.getVideoId() + "/original");
        when(videos.findForUpdate(selected.getVideoId())).thenReturn(Optional.of(retried));

        job.sweep();

        verify(queue, never()).saveAndFlush(any());
    }

    @Test
    void aVideoThatAlreadyHasASourcePurgeIsNotScheduledTwice() {
        VideoEntity video = failed("TERMINAL");
        due(video);
        when(queue.existsByVideoIdAndPurgePrefixIsNotNull(video.getVideoId())).thenReturn(true);

        job.sweep();

        verify(queue, never()).saveAndFlush(any());
    }

    @Test
    void oneVideoFailingDoesNotStopTheRest() {
        VideoEntity broken = failed("TERMINAL");
        VideoEntity healthy = failed("TERMINAL");
        due(broken, healthy);
        doThrow(new IllegalStateException("db"))
                .when(queue)
                .saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                        row -> row != null && row.getVideoId().equals(broken.getVideoId())));

        job.sweep();

        assertThat(meters.get("video.failed_source.scheduled").counter().count()).isEqualTo(1.0);
    }
}
