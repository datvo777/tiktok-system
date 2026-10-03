package com.shortvideo.upload.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.video.api.VideoDraftRegistrar;
import io.minio.MinioClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
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

    private static UploadSessionEntity session(String objectKey) {
        UploadSessionEntity session = mock(UploadSessionEntity.class);
        when(session.getObjectKey()).thenReturn(objectKey);
        when(session.getUploadId()).thenReturn(UUID.randomUUID());
        when(session.getVideoId()).thenReturn(UUID.randomUUID());
        when(session.getStatus()).thenReturn(UploadStatus.PENDING);
        return session;
    }

    private static UploadJpaRepository repositoryReturning(List<UploadSessionEntity> due) {
        UploadJpaRepository repository = mock(UploadJpaRepository.class);
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any(), any(Pageable.class)))
                .thenReturn(due);
        due.forEach(session -> when(repository.findById(session.getUploadId())).thenReturn(Optional.of(session)));
        return repository;
    }

    @Test
    void oneSessionFailingToReapDoesNotStopTheRestOfTheBatch() throws Exception {
        UploadSessionEntity broken = session("uploads/broken");
        UploadSessionEntity healthy = session("uploads/healthy");
        UploadJpaRepository repository = repositoryReturning(List.of(broken, healthy));
        VideoDraftRegistrar registrar = mock(VideoDraftRegistrar.class);
        String brokenVideoId = broken.getVideoId().toString();
        doThrow(new IllegalStateException("db down")).when(registrar).expireDraft(brokenVideoId);
        UploadCleanupJob job = new UploadCleanupJob(
                repository,
                mock(MinioClient.class),
                registrar,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                "bucket");

        job.sweep();

        verify(repository, never()).delete(broken);
        verify(repository).delete(healthy);
    }

    @Test
    void sessionIsKeptWhenTheObjectCannotBeRemoved() throws Exception {
        UploadSessionEntity stuck = session("uploads/stuck");
        UploadJpaRepository repository = repositoryReturning(List.of(stuck));
        VideoDraftRegistrar registrar = mock(VideoDraftRegistrar.class);
        MinioClient minio = mock(MinioClient.class);
        doThrow(new IllegalStateException("minio down")).when(minio).removeObject(any());
        UploadCleanupJob job = new UploadCleanupJob(
                repository, minio, registrar, new TransactionTemplate(mock(PlatformTransactionManager.class)), "bucket");

        job.sweep();

        verify(registrar, never()).expireDraft(any());
        verify(repository, never()).delete(stuck);
    }

    @Test
    void aSessionCompletedWhileItsObjectWasBeingRemovedIsLeftAlone() {
        UploadSessionEntity listed = session("uploads/raced");
        UploadJpaRepository repository = repositoryReturning(List.of(listed));
        UploadSessionEntity completedMeanwhile = mock(UploadSessionEntity.class);
        when(completedMeanwhile.getStatus()).thenReturn(UploadStatus.COMPLETED);
        when(repository.findById(listed.getUploadId())).thenReturn(Optional.of(completedMeanwhile));
        VideoDraftRegistrar registrar = mock(VideoDraftRegistrar.class);
        UploadCleanupJob job = new UploadCleanupJob(
                repository,
                mock(MinioClient.class),
                registrar,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                "bucket");

        job.sweep();

        verify(registrar, never()).expireDraft(any());
        verify(repository, never()).delete(any());
    }
}
