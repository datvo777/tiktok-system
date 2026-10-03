package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.video.api.AssetLifecycleState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class SupersededAssetCleanupJobTest {

    private final SupersededAssetJpaRepository repository = mock(SupersededAssetJpaRepository.class);
    private final MinioAssetPurger purger = mock(MinioAssetPurger.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SupersededAssetCleanupJob job = new SupersededAssetCleanupJob(repository, purger, meters);

    private void scheduled(SupersededAssetEntity... rows) {
        when(repository.findByStateOrderByCreatedAtAsc(
                        org.mockito.ArgumentMatchers.eq(AssetLifecycleState.DELETE_SCHEDULED), any(Pageable.class)))
                .thenReturn(List.of(rows));
        when(repository.findByStateOrderByCreatedAtAsc(
                        org.mockito.ArgumentMatchers.eq(AssetLifecycleState.DELETION_IN_PROGRESS), any(Pageable.class)))
                .thenReturn(List.of());
    }

    @Test
    void aRowIsOnlyMarkedDeletedOnceThePurgeSucceeded() {
        SupersededAssetEntity row = new SupersededAssetEntity(UUID.randomUUID(), 2, null, List.of());
        scheduled(row);

        job.sweep();

        assertThat(row.getState()).isEqualTo(AssetLifecycleState.DELETED);
        verify(purger).purgePrefix("processed/" + row.getVideoId() + "/2/");
        assertThat(meters.get("video.cleanup.purged").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aFailedPurgeLeavesTheRowToBeRetriedAndDoesNotStopTheBatch() {
        SupersededAssetEntity broken = new SupersededAssetEntity(UUID.randomUUID(), 1, null, List.of());
        SupersededAssetEntity healthy = new SupersededAssetEntity(UUID.randomUUID(), 1, null, List.of());
        scheduled(broken, healthy);
        doThrow(new MinioAssetPurger.AssetPurgeException("minio down", null))
                .when(purger)
                .purgePrefix(broken.prefix());

        job.sweep();

        // Not DELETED: nothing was removed, so the next sweep must pick it up again.
        assertThat(broken.getState()).isEqualTo(AssetLifecycleState.DELETION_IN_PROGRESS);
        assertThat(healthy.getState()).isEqualTo(AssetLifecycleState.DELETED);
        assertThat(meters.get("video.cleanup.failed").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("video.cleanup.purged").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aSourceRowPurgesTheSourcePrefixNotAProcessedOne() {
        UUID videoId = UUID.randomUUID();
        SupersededAssetEntity row = SupersededAssetEntity.sourceOf(videoId);
        scheduled(row);

        job.sweep();

        verify(purger).purgePrefix("sources/" + videoId + "/");
        verify(purger, never()).purgePrefix("processed/" + videoId + "/0/");
        assertThat(row.getState()).isEqualTo(AssetLifecycleState.DELETED);
    }
}
