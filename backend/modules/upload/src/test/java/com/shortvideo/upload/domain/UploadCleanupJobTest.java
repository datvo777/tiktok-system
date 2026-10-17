package com.shortvideo.upload.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.video.api.VideoDraftRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        when(repository.findReapable(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        UploadCleanupJob job = new UploadCleanupJob(
                repository, mock(MinioClient.class), mock(VideoDraftRegistrar.class), mock(TransactionTemplate.class), "bucket", new SimpleMeterRegistry());

        Instant before = Instant.now();
        job.sweep();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findReapable(any(), cutoff.capture(), any(), any(Pageable.class));
        // A session that expired a minute ago is inside the grace and must not be selected.
        assertThat(cutoff.getValue())
                .isBetween(
                        before.minus(UploadCleanupJob.REAP_AFTER),
                        after.minus(UploadCleanupJob.REAP_AFTER));
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
        when(repository.findReapable(any(), any(), any(), any(Pageable.class)))
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
                "bucket", new SimpleMeterRegistry());

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
                repository, minio, registrar, new TransactionTemplate(mock(PlatformTransactionManager.class)), "bucket", new SimpleMeterRegistry());

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
                "bucket", new SimpleMeterRegistry());

        job.sweep();

        verify(registrar, never()).expireDraft(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void theCountersSeparateReapedFromFailedAndDoNotCountASkippedSessionAsAFailure() throws Exception {
        UploadSessionEntity healthy = session("uploads/healthy");
        UploadSessionEntity stuck = session("uploads/stuck");
        UploadSessionEntity raced = session("uploads/raced");
        UploadJpaRepository repository = repositoryReturning(List.of(healthy, stuck, raced));
        UploadSessionEntity completedMeanwhile = mock(UploadSessionEntity.class);
        when(completedMeanwhile.getStatus()).thenReturn(UploadStatus.COMPLETED);
        when(repository.findById(raced.getUploadId())).thenReturn(Optional.of(completedMeanwhile));
        MinioClient minio = mock(MinioClient.class);
        doThrow(new IllegalStateException("minio down")).when(minio).removeObject(argThat(args -> "uploads/stuck".equals(args.object())));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UploadCleanupJob job = new UploadCleanupJob(
                repository,
                minio,
                mock(VideoDraftRegistrar.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                "bucket",
                meters);

        job.sweep();

        assertThat(meters.get("upload.cleanup.reaped").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("upload.cleanup.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aSessionThatFailsToReapIsBackedOffSoItStopsHoldingTheHeadOfTheQueue() throws Exception {
        UploadSessionEntity stuck = session("uploads/stuck");
        when(stuck.getReapAttempts()).thenReturn(2);
        UploadJpaRepository repository = repositoryReturning(List.of(stuck));
        MinioClient minio = mock(MinioClient.class);
        doThrow(new IllegalStateException("minio down")).when(minio).removeObject(any());
        UploadCleanupJob job = new UploadCleanupJob(
                repository, minio, mock(VideoDraftRegistrar.class), new TransactionTemplate(mock(PlatformTransactionManager.class)), "bucket", new SimpleMeterRegistry());

        Instant before = Instant.now();
        job.sweep();
        Instant after = Instant.now();

        // Third failure: the delay is 30m * 2^2.
        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(repository).recordReapFailure(org.mockito.ArgumentMatchers.eq(stuck.getUploadId()), next.capture());
        assertThat(next.getValue())
                .isBetween(before.plus(UploadCleanupJob.backoff(3)), after.plus(UploadCleanupJob.backoff(3)));
    }

    @Test
    void aFailureInTheDatabaseStepIsBackedOffToo() {
        UploadSessionEntity broken = session("uploads/broken");
        UploadJpaRepository repository = repositoryReturning(List.of(broken));
        VideoDraftRegistrar registrar = mock(VideoDraftRegistrar.class);
        String brokenVideoId = broken.getVideoId().toString();
        doThrow(new IllegalStateException("db down")).when(registrar).expireDraft(brokenVideoId);
        UploadCleanupJob job = new UploadCleanupJob(
                repository, mock(MinioClient.class), registrar, new TransactionTemplate(mock(PlatformTransactionManager.class)), "bucket", new SimpleMeterRegistry());

        job.sweep();

        verify(repository).recordReapFailure(org.mockito.ArgumentMatchers.eq(broken.getUploadId()), any());
    }

    @Test
    void reapedAndSkippedSessionsAreNotBackedOff() {
        UploadSessionEntity healthy = session("uploads/healthy");
        UploadJpaRepository repository = repositoryReturning(List.of(healthy));
        UploadCleanupJob job = new UploadCleanupJob(
                repository, mock(MinioClient.class), mock(VideoDraftRegistrar.class), new TransactionTemplate(mock(PlatformTransactionManager.class)), "bucket", new SimpleMeterRegistry());

        job.sweep();

        verify(repository, never()).recordReapFailure(any(), any());
    }

    @Test
    void aFailureRecordingErrorDoesNotAbortTheRestOfTheBatch() throws Exception {
        UploadSessionEntity stuck = session("uploads/stuck");
        UploadSessionEntity healthy = session("uploads/healthy");
        UploadJpaRepository repository = repositoryReturning(List.of(stuck, healthy));
        when(repository.recordReapFailure(any(), any())).thenThrow(new IllegalStateException("db down"));
        MinioClient minio = mock(MinioClient.class);
        doThrow(new IllegalStateException("minio down")).when(minio).removeObject(argThat(args -> "uploads/stuck".equals(args.object())));
        UploadCleanupJob job = new UploadCleanupJob(
                repository, minio, mock(VideoDraftRegistrar.class), new TransactionTemplate(mock(PlatformTransactionManager.class)), "bucket", new SimpleMeterRegistry());

        job.sweep();

        verify(repository).delete(healthy);
    }

    @Test
    void theBackoffDoublesFromThirtyMinutesAndStopsAtSixHours() {
        assertThat(UploadCleanupJob.backoff(1)).isEqualTo(java.time.Duration.ofMinutes(30));
        assertThat(UploadCleanupJob.backoff(2)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(UploadCleanupJob.backoff(3)).isEqualTo(java.time.Duration.ofHours(2));
        assertThat(UploadCleanupJob.backoff(4)).isEqualTo(java.time.Duration.ofHours(4));
        assertThat(UploadCleanupJob.backoff(5)).isEqualTo(java.time.Duration.ofHours(6));
        assertThat(UploadCleanupJob.backoff(1_000)).isEqualTo(java.time.Duration.ofHours(6));
    }
}
