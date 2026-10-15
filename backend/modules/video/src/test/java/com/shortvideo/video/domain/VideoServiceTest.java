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

    private static final io.micrometer.core.instrument.simple.SimpleMeterRegistry METERS =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

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
                assetsOf(videoId, version),
                null);

        apply(service, result);

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
    void reprocessingAFailedVideoRecordsWhatItFailedWithInTheAudit() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var audit = mock(com.shortvideo.shared.audit.AdminActionRecorder.class);
        VideoService service = new VideoService(
                repository,
                mock(SupersededAssetJpaRepository.class),
                mock(com.shortvideo.shared.outbox.OutboxWriter.class),
                mock(MinioAssetVerifier.class),
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                audit,
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        video.dispatchProcessing("sources/" + videoId + "/original");
        video.markFailed("TERMINAL");
        when(repository.findForUpdate(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        service.reprocess(videoId.toString(), UUID.randomUUID().toString());

        var action = org.mockito.ArgumentCaptor.forClass(com.shortvideo.shared.audit.AdminAction.class);
        org.mockito.Mockito.verify(audit).record(action.capture());
        assertThat(action.getValue().reason()).isEqualTo("from FAILED/TERMINAL");
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

    /** What the listener does: verify first, outside any transaction, then apply. */
    private static void apply(VideoService service, com.shortvideo.shared.events.MediaEvents.MediaResultCommand result) {
        service.applyMediaResult(result, service.verifyAssets(result));
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
        apply(service, failedResult(videoId, version, "TRANSIENT"));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        assertThat(video.getTranscodeAttempt()).isEqualTo(2);
        var envelope = org.mockito.ArgumentCaptor.forClass(com.shortvideo.shared.events.EventEnvelope.class);
        var availableAt = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        org.mockito.Mockito.verify(outbox).append(envelope.capture(), availableAt.capture());
        assertThat(envelope.getValue().eventType())
                .isEqualTo(com.shortvideo.shared.events.EventTypes.MEDIA_JOB_DISPATCHED);
        assertThat(((com.shortvideo.shared.events.MediaEvents.MediaJobCommand) envelope.getValue().payload()).jobId())
                .isEqualTo(videoId + ":" + version);
        // 30s base, spread over +/-20%
        assertThat(availableAt.getValue()).isBetween(before.plusSeconds(24), java.time.Instant.now().plusSeconds(36));
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
        apply(service, failedResult(videoId, version, "TRANSIENT"));

        var availableAt = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        org.mockito.Mockito.verify(outbox).append(org.mockito.ArgumentMatchers.any(), availableAt.capture());
        assertThat(availableAt.getValue()).isBetween(before.plusSeconds(48), java.time.Instant.now().plusSeconds(72));
    }

    @Test
    void aTransientFailureOnTheLastAttemptFailsTheVideoAndAnnouncesIt() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        var superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWithOutbox(repository, outbox, 2, superseded);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.retryTranscode();
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, failedResult(videoId, version, "TRANSIENT"));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
        assertThat(video.getFailureClass()).isEqualTo("TRANSIENT");
        var envelope = org.mockito.ArgumentCaptor.forClass(com.shortvideo.shared.events.EventEnvelope.class);
        org.mockito.Mockito.verify(outbox).append(envelope.capture());
        assertThat(envelope.getValue().eventType())
                .isEqualTo(com.shortvideo.shared.events.EventTypes.VIDEO_PROCESSING_FAILED);
        // The worker may have copied part of its output before it failed; that prefix is reclaimed.
        var rows = org.mockito.ArgumentCaptor.forClass(SupersededAssetEntity.class);
        org.mockito.Mockito.verify(superseded).saveAndFlush(rows.capture());
        assertThat(rows.getValue().prefix()).isEqualTo("processed/" + videoId + "/" + version + "/");
    }

    @Test
    void theRetryDelayIsSpreadOverTwentyPercentEitherSideOfTheBackoff() {
        java.time.Duration base = java.time.Duration.ofSeconds(60);

        assertThat(VideoService.jittered(base, 0.0)).isEqualTo(java.time.Duration.ofSeconds(48));
        assertThat(VideoService.jittered(base, 0.5)).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(VideoService.jittered(base, 0.999)).isBetween(
                java.time.Duration.ofMillis(71_900), java.time.Duration.ofSeconds(72));
    }

    @Test
    void retriesAndExhaustedFailuresAreCountedByFailureClass() {
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        VideoService service = new VideoService(
                repository,
                mock(SupersededAssetJpaRepository.class),
                mock(com.shortvideo.shared.outbox.OutboxWriter.class),
                mock(MinioAssetVerifier.class),
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class),
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                2,
                java.time.Duration.ofSeconds(30),
                meters);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, failedResult(videoId, version, "TRANSIENT")); // retried
        apply(service, failedResult(videoId, version, "TRANSIENT")); // budget spent

        assertThat(meters.counter("video.transcode.retried").count()).isEqualTo(1.0);
        assertThat(meters.counter("video.transcode.failed", "failureClass", "TRANSIENT").count()).isEqualTo(1.0);
        assertThat(meters.counter("video.transcode.failed", "failureClass", "TERMINAL").count()).isZero();
    }

    private static com.shortvideo.shared.events.MediaEvents.Assets assetsOf(UUID videoId, int version) {
        String prefix = "processed/" + videoId + "/" + version + "/";
        return new com.shortvideo.shared.events.MediaEvents.Assets(
                prefix + "master.m3u8", List.of(prefix + "high/index.m3u8"), 3, 4.0);
    }

    private static com.shortvideo.shared.events.MediaEvents.MediaResultCommand completedResult(
            UUID videoId, int version) {
        return new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version,
                videoId.toString(),
                version,
                "COMPLETED",
                assetsOf(videoId, version),
                null);
    }

    private static VideoService serviceWithVerifier(
            VideoJpaRepository repository,
            com.shortvideo.shared.outbox.OutboxWriter outbox,
            SupersededAssetJpaRepository superseded,
            MinioAssetVerifier verifier,
            com.shortvideo.shared.audit.AdminActionRecorder audit) {
        return new VideoService(
                repository,
                superseded,
                outbox,
                verifier,
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                audit,
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                3,
                java.time.Duration.ofSeconds(30),
                METERS);
    }

    @Test
    void aCompletedResultWhoseAssetsFailVerificationIsRetriedAsTransient() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        MinioAssetVerifier verifier = mock(MinioAssetVerifier.class);
        when(verifier.verify(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(false);
        VideoService service = serviceWithVerifier(
                repository, outbox, mock(SupersededAssetJpaRepository.class), verifier,
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, completedResult(videoId, version));

        // The worker said it was done but the objects are not there: not READY, and tried again.
        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        assertThat(video.getTranscodeAttempt()).isEqualTo(2);
        org.mockito.Mockito.verify(outbox).append(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
    }

    @Test
    void aCompletedResultNamingAnotherVideosObjectsIsNotDurableAndNeverReachesTheStore() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        MinioAssetVerifier verifier = mock(MinioAssetVerifier.class);
        VideoService service = serviceWithVerifier(
                repository, mock(com.shortvideo.shared.outbox.OutboxWriter.class),
                mock(SupersededAssetJpaRepository.class), verifier,
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        String other = "processed/" + UUID.randomUUID() + "/" + version + "/";
        var result = new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version, videoId.toString(), version, "COMPLETED",
                new com.shortvideo.shared.events.MediaEvents.Assets(
                        other + "master.m3u8", List.of(other + "high/index.m3u8"), 3, 4.0),
                null);
        var escaping = new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version, videoId.toString(), version, "COMPLETED",
                new com.shortvideo.shared.events.MediaEvents.Assets(
                        "processed/" + videoId + "/" + version + "/../x/master.m3u8", List.of(), 3, 4.0),
                null);

        assertThat(service.verifyAssets(result)).isFalse();
        assertThat(service.verifyAssets(escaping)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(verifier);
    }

    @Test
    void aResultForAnOlderProcessingVersionChangesNothing() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        var superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWithVerifier(
                repository, outbox, superseded, mock(MinioAssetVerifier.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int firstVersion = video.dispatchProcessing("sources/" + videoId + "/original");
        video.markFailed("TERMINAL");
        video.dispatchReprocessing("sources/" + videoId + "/original"); // now version 2, TRANSCODING
        when(repository.findById(videoId)).thenReturn(Optional.of(video));

        apply(service, failedResult(videoId, firstVersion, "TERMINAL"));
        apply(service, completedResult(videoId, firstVersion));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        org.mockito.Mockito.verifyNoInteractions(outbox, superseded);
    }

    @Test
    void reprocessingAVideoThatIsAlreadyTranscodingDoesNothing() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        var audit = mock(com.shortvideo.shared.audit.AdminActionRecorder.class);
        VideoService service = serviceWithVerifier(
                repository, outbox, mock(SupersededAssetJpaRepository.class), mock(MinioAssetVerifier.class), audit);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findForUpdate(videoId)).thenReturn(Optional.of(video));

        service.reprocess(videoId.toString(), UUID.randomUUID().toString());

        // A duplicate click or a redelivered request: no second job, no audit row, same version.
        assertThat(video.getProcessingVersion()).isEqualTo(version);
        org.mockito.Mockito.verifyNoInteractions(outbox, audit);
    }

    @Test
    void aCompletedResultThatArrivesAfterTheVideoWasFailedHasItsOutputPurged() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWithVerifier(
                repository, mock(com.shortvideo.shared.outbox.OutboxWriter.class), superseded,
                mock(MinioAssetVerifier.class), mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.markFailed("TRANSIENT"); // the watchdog got there first
        when(repository.findById(videoId)).thenReturn(Optional.of(video));

        apply(service, completedResult(videoId, version));

        // The worker wrote under processed/ after the purge scheduled at failure time; nothing serves it.
        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
        var rows = org.mockito.ArgumentCaptor.forClass(SupersededAssetEntity.class);
        org.mockito.Mockito.verify(superseded).saveAndFlush(rows.capture());
        assertThat(rows.getValue().prefix()).isEqualTo("processed/" + videoId + "/" + version + "/");
    }

    @Test
    void aDuplicateCompletedResultForAReadyVideoPurgesNothing() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWithVerifier(
                repository, mock(com.shortvideo.shared.outbox.OutboxWriter.class), superseded,
                mock(MinioAssetVerifier.class), mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.markReady("processed/m.m3u8", List.of("processed/v.m3u8"), 3, 4.0);
        when(repository.findById(videoId)).thenReturn(Optional.of(video));

        apply(service, completedResult(videoId, version));

        // Those objects are the live ones.
        org.mockito.Mockito.verifyNoInteractions(superseded);
    }

    private static com.shortvideo.shared.events.MediaEvents.MediaResultCommand timedOutResult(UUID videoId, int version) {
        return new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version, videoId.toString(), version, "FAILED", null, "TRANSIENT",
                com.shortvideo.shared.events.MediaEvents.MediaResultCommand.TIMEOUT);
    }

    @Test
    void aVideoThatKeepsTimingOutGetsTwoTriesNotTheWholeBudget() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 5); // a budget of five for anything else
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, timedOutResult(videoId, version)); // first try timed out: one more allowed
        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        assertThat(video.getTranscodeAttempt()).isEqualTo(2);

        apply(service, timedOutResult(videoId, version)); // second timed out: no third

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
        assertThat(video.getFailureClass()).isEqualTo("TRANSIENT");
    }

    @Test
    void aTimeoutNeverGetsMoreTriesThanTheConfiguredBudget() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 1);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, timedOutResult(videoId, version));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
    }

    @Test
    void aTransientFailureWithoutAReasonKeepsTheFullBudget() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        VideoService service = serviceWithOutbox(repository, outbox, 5);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        video.retryTranscode();
        when(repository.findById(videoId)).thenReturn(Optional.of(video)); // already on its second try
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, failedResult(videoId, version, "TRANSIENT"));

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        assertThat(video.getTranscodeAttempt()).isEqualTo(3);
    }

    @Test
    void aTerminalFailureIsNeverRetried() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        var superseded = mock(SupersededAssetJpaRepository.class);
        VideoService service = serviceWithOutbox(repository, outbox, 3, superseded);
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(repository.saveAndFlush(video)).thenReturn(video);

        apply(service, failedResult(videoId, version, "TERMINAL"));

        // Reported before anything was promoted, so there is no output to reclaim.
        org.mockito.Mockito.verifyNoInteractions(superseded);

        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.FAILED);
        assertThat(video.getTranscodeAttempt()).isEqualTo(1);
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.never())
                .append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static VideoService serviceWithOutbox(
            VideoJpaRepository repository, com.shortvideo.shared.outbox.OutboxWriter outbox, int maxAttempts) {
        return serviceWithOutbox(repository, outbox, maxAttempts, mock(SupersededAssetJpaRepository.class));
    }

    private static VideoService serviceWithOutbox(
            VideoJpaRepository repository,
            com.shortvideo.shared.outbox.OutboxWriter outbox,
            int maxAttempts,
            SupersededAssetJpaRepository superseded) {
        return new VideoService(
                repository,
                superseded,
                outbox,
                mock(MinioAssetVerifier.class),
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class),
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                maxAttempts,
                java.time.Duration.ofSeconds(30),
                METERS);
    }

    @Test
    void aStoreThatCannotBeAskedLeavesTheVideoTranscodingAndFailsTheDelivery() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        var outbox = mock(com.shortvideo.shared.outbox.OutboxWriter.class);
        MinioAssetVerifier verifier = mock(MinioAssetVerifier.class);
        VideoService service = new VideoService(
                repository,
                mock(SupersededAssetJpaRepository.class),
                outbox,
                verifier,
                mock(com.shortvideo.shared.revocation.DurableRevocationWriter.class),
                mock(com.shortvideo.shared.audit.AdminActionRecorder.class),
                null,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        when(verifier.verify(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new VideoExceptions.AssetStoreUnavailable("MinIO down", new RuntimeException()));
        var result = new com.shortvideo.shared.events.MediaEvents.MediaResultCommand(
                videoId + ":" + version,
                videoId.toString(),
                version,
                "COMPLETED",
                assetsOf(videoId, version),
                null);

        assertThat(catchThrowable(() -> apply(service, result)))
                .isInstanceOf(VideoExceptions.AssetStoreUnavailable.class);

        // Not failed and not retried as a transcode: nothing was decided, so the delivery is retried.
        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
        assertThat(video.getTranscodeAttempt()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(outbox);
    }

    private double ignoredCount(String reason) {
        return METERS.counter("video.transcode.result.ignored", "reason", reason).count();
    }

    @Test
    void everyKindOfIgnoredResultIsCountedByReason() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        VideoService service = serviceWithVerifier(
                repository, mock(com.shortvideo.shared.outbox.OutboxWriter.class), mock(SupersededAssetJpaRepository.class),
                mock(MinioAssetVerifier.class), mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        double unknown = ignoredCount("unknown_video");
        double stale = ignoredCount("stale_version");
        double terminal = ignoredCount("already_terminal");
        double removed = ignoredCount("removed");

        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        apply(service, failedResult(missing, 1, "TERMINAL"));

        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));
        apply(service, failedResult(videoId, version + 1, "TERMINAL")); // not the current version
        video.markFailed("TERMINAL");
        apply(service, failedResult(videoId, version, "TERMINAL")); // already decided

        UUID goneId = UUID.randomUUID();
        VideoEntity gone = new VideoEntity(goneId, UUID.randomUUID(), "title", "desc");
        int goneVersion = gone.dispatchProcessing("sources/" + goneId + "/original");
        gone.scheduleForDeletion();
        when(repository.findById(goneId)).thenReturn(Optional.of(gone));
        apply(service, failedResult(goneId, goneVersion, "TERMINAL"));

        assertThat(ignoredCount("unknown_video") - unknown).isEqualTo(1.0);
        assertThat(ignoredCount("stale_version") - stale).isEqualTo(1.0);
        assertThat(ignoredCount("already_terminal") - terminal).isEqualTo(1.0);
        assertThat(ignoredCount("removed") - removed).isEqualTo(1.0);
    }

    @Test
    void aResultAppliedWithoutHavingBeenVerifiedIsAProgrammingError() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        VideoService service = serviceWithVerifier(
                repository, mock(com.shortvideo.shared.outbox.OutboxWriter.class), mock(SupersededAssetJpaRepository.class),
                mock(MinioAssetVerifier.class), mock(com.shortvideo.shared.audit.AdminActionRecorder.class));
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, UUID.randomUUID(), "title", "desc");
        int version = video.dispatchProcessing("sources/" + videoId + "/original");
        when(repository.findById(videoId)).thenReturn(Optional.of(video));

        assertThat(catchThrowable(() -> service.applyMediaResult(completedResult(videoId, version), null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(video.getProcessingState()).isEqualTo(ProcessingState.TRANSCODING);
    }
}
