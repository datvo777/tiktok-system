package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.video.api.ProcessingState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Existence non-disclosure (brief section 12.3): a non-owner polling a video
 * that exists must see exactly what they would see for a video id that does
 * not exist at all — otherwise the response itself reveals that the id is
 * real, just not theirs.
 */
class VideoServiceTest {

    @Test
    void nonOwnerAndMissingVideoProduceTheIdenticalNotFoundResponse() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        VideoService service = new VideoService(repository, null, null, null, null, null, null, null);

        UUID existingVideoId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String otherCaller = UUID.randomUUID().toString();
        when(repository.findById(existingVideoId))
                .thenReturn(Optional.of(new VideoEntity(existingVideoId, ownerId, "title", "desc")));

        UUID missingVideoId = UUID.randomUUID();
        when(repository.findById(missingVideoId)).thenReturn(Optional.empty());

        Throwable nonOwner =
                catchThrowable(() -> service.findForPolling(existingVideoId.toString(), otherCaller));
        Throwable notFound =
                catchThrowable(() -> service.findForPolling(missingVideoId.toString(), otherCaller));

        assertThat(nonOwner).isInstanceOf(VideoExceptions.VideoNotFound.class);
        assertThat(notFound).isInstanceOf(VideoExceptions.VideoNotFound.class);
        assertThat(nonOwner.getMessage()).isEqualTo(notFound.getMessage());
    }

    @Test
    void removingAVideoAlsoSchedulesItsSourceForPurge() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        SupersededAssetJpaRepository superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = new VideoService(
                repository,
                superseded,
                mock(com.shortvideo.shared.outbox.OutboxWriter.class),
                null,
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class),
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        service.remove(videoId.toString(), "spam", UUID.randomUUID().toString());

        var rows = org.mockito.ArgumentCaptor.forClass(SupersededAssetEntity.class);
        org.mockito.Mockito.verify(superseded, org.mockito.Mockito.atLeastOnce()).saveAndFlush(rows.capture());
        assertThat(rows.getAllValues()).extracting(SupersededAssetEntity::prefix).contains("sources/" + videoId + "/");
    }

    private static VideoService serviceWith(VideoJpaRepository repository, SupersededAssetJpaRepository superseded) {
        return new VideoService(
                repository,
                superseded,
                mock(com.shortvideo.shared.outbox.OutboxWriter.class),
                mock(MinioAssetVerifier.class),
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class),
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @Test
    void aTranscodeResultForARemovedVideoIsNotAppliedAndItsOutputIsScheduledForPurge() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        SupersededAssetJpaRepository superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWith(repository, superseded);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.scheduleForDeletion();
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        var result = new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version,
                videoId.toString(),
                version,
                "COMPLETED",
                new com.shortvideo.shared.events.MediaEvents.Assets("processed/m.m3u8", List.of("processed/v.m3u8"), 3, 4.0),
                null);

        service.applyMediaResult(result);

        // Still TRANSCODING: never marked READY, and no event announcing it.
        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAndFlush(video);
        var rows = org.mockito.ArgumentCaptor.forClass(SupersededAssetEntity.class);
        org.mockito.Mockito.verify(superseded).saveAndFlush(rows.capture());
        assertThat(rows.getValue().prefix()).isEqualTo("processed/" + videoId + "/" + version + "/");
    }

    @Test
    void aRemovedVideoCannotBeReprocessed() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        VideoService service = serviceWith(repository, mock(SupersededAssetJpaRepository.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        video.dispatchProcessing("sources/" + videoId + "/original");
        video.scheduleForDeletion();
        when(repository.findForUpdate(videoId)).thenReturn(Optional.of(video));

        assertThat(catchThrowable(() -> service.reprocess(videoId.toString(), UUID.randomUUID().toString())))
                .isInstanceOf(VideoExceptions.VideoNotFound.class);
    }

    @Test
    void aVideoWhoseSourceWasReclaimedCannotBeReprocessed() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        SupersededAssetJpaRepository superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWith(repository, superseded);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        video.dispatchProcessing("sources/" + videoId + "/original");
        video.markFailed("TERMINAL");
        when(repository.findForUpdate(videoId)).thenReturn(Optional.of(video));
        when(superseded.existsByVideoIdAndPurgePrefixIsNotNull(videoId)).thenReturn(true);

        assertThat(catchThrowable(() -> service.reprocess(videoId.toString(), UUID.randomUUID().toString())))
                .isInstanceOf(VideoExceptions.VideoNotReady.class);
    }

    @Test
    void anExpiredDraftHasItsSourceScheduledForPurge() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        SupersededAssetJpaRepository superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWith(repository, superseded);
        UUID videoId = UUID.randomUUID();
        VideoEntity draft = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        when(repository.findById(videoId)).thenReturn(Optional.of(draft));

        service.expireDraft(videoId.toString());

        var rows = org.mockito.ArgumentCaptor.forClass(SupersededAssetEntity.class);
        org.mockito.Mockito.verify(superseded).saveAndFlush(rows.capture());
        assertThat(rows.getValue().prefix()).isEqualTo("sources/" + videoId + "/");
    }

    @Test
    void aDraftThatAlreadyStartedProcessingKeepsItsSource() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        SupersededAssetJpaRepository superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWith(repository, superseded);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));

        // The reaper racing a completion must never reach the source a completed upload is using.
        service.expireDraft(videoId.toString());

        org.mockito.Mockito.verify(superseded, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    private static com.shortvideo.shared.events.MediaEvents.MediaResultCommand failedResult(
            UUID videoId, int version, String failureClass) {
        return new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version, videoId.toString(), version, "FAILED", null, failureClass);
    }

    @Test
    void aTransientFailureRedispatchesTheSameJobAfterABackoffAndKeepsTheVideoTranscoding() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 3);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        java.time.Instant before = java.time.Instant.now();
        service.applyMediaResult(failedResult(videoId, version, "TRANSIENT"));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        assertThat(video.getTranscodeAttempt()).isEqualTo(2);
        var envelope = org.mockito.ArgumentCaptor.forClass(com.shortvideo.shared.events.EventEnvelope.class);
        var availableAt = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        org.mockito.Mockito.verify(outbox).append(envelope.capture(), availableAt.capture());
        assertThat(envelope.getValue().eventType())
                .isEqualTo(com.shortvideo.shared.events.EventTypes.MEDIA_JOB_DISPATCHED);
        assertThat(((com.shortvideo.shared.events.MediaEvents.MediaJobCommand) envelope.getValue().payload()).jobId())
                .isEqualTo(videoId + ":" + version);
        assertThat(availableAt.getValue()).isAfterOrEqualTo(before.plusSeconds(30));
    }

    @Test
    void theRetryBackoffDoublesWithEachFailedAttempt() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 5);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.retryTranscode(); // attempt 2 has just failed
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        java.time.Instant before = java.time.Instant.now();
        service.applyMediaResult(failedResult(videoId, version, "TRANSIENT"));

        var availableAt = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        org.mockito.Mockito.verify(outbox).append(org.mockito.ArgumentMatchers.any(), availableAt.capture());
        assertThat(availableAt.getValue()).isAfterOrEqualTo(before.plusSeconds(60));
    }

    @Test
    void aTransientFailureOnTheLastAttemptFailsTheVideoAndAnnouncesIt() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 2);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.retryTranscode();
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        service.applyMediaResult(failedResult(videoId, version, "TRANSIENT"));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
        assertThat(video.getFailureClass()).isEqualTo("TRANSIENT");
        var envelope = org.mockito.ArgumentCaptor.forClass(com.shortvideo.shared.events.EventEnvelope.class);
        org.mockito.Mockito.verify(outbox).append(envelope.capture());
        assertThat(envelope.getValue().eventType())
                .isEqualTo(com.shortvideo.shared.events.EventTypes.VIDEO_PROCESSING_FAILED);
    }

    @Test
    void aTerminalFailureIsNeverRetried() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 3);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        service.applyMediaResult(failedResult(videoId, version, "TERMINAL"));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
        assertThat(video.getTranscodeAttempt()).isEqualTo(1);
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.never())
                .append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static VideoService serviceWithOutbox(
            VideoJpaRepository repository, com.shortvideo.shared.outbox.OutboxWriter outbox, int maxAttempts) {
        return new VideoService(
                repository,
                mock(SupersededAssetJpaRepository.class),
                outbox,
                mock(MinioAssetVerifier.class),
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class),
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                maxAttempts,
                java.time.Duration.ofSeconds(30));
    }
}
