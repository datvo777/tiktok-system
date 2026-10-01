package com.shortvideo.upload.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.MinioClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

class UploadCleanupJobTest {

    @Test
    void sessionsAreNotReapedUntilTheCompletionGraceHasPassedAfterExpiry() {
        UploadJpaRepository repository = mock(UploadJpaRepository.class);
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        UploadCleanupJob job = new UploadCleanupJob(
                repository, mock(MinioClient.class), mock(VideoDraftRegistrar.class), mock(TransactionTemplate.class), "bucket");

        Instant before = Instant.now();
        job.sweep();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), cutoff.capture(), any(Pageable.class));
        // A session that expired a minute ago is inside the grace and must not be selected.
        assertThat(cutoff.getValue())
                .isBetween(
                        before.minus(UploadSessionEntity.COMPLETION_GRACE),
                        after.minus(UploadSessionEntity.COMPLETION_GRACE));
        assertThat(cutoff.getValue()).isBefore(after.minusSeconds(60));
    }
}
