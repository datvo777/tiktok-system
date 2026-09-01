package com.shortvideo.video.domain;

import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.shared.revocation.DurableRevocationWriter;
import com.shortvideo.shared.revocation.RevocationClearCommand;
import com.shortvideo.shared.revocation.RevocationCommand;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoDraft;
import com.shortvideo.video.api.VideoDraftRegistrar;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import com.shortvideo.video.api.VideoView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class VideoService implements VideoDraftRegistrar, VideoPlaybackDirectory {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "video";
    private static final List<String> DEFAULT_RENDITIONS = List.of("720p");

    /** Revocation source type for admin lifecycle holds (quarantine/removal — brief section 16). */
    public static final String LIFECYCLE_SOURCE = "LIFECYCLE";

    private final VideoJpaRepository repository;
    private final SupersededAssetJpaRepository supersededAssetRepository;
    private final OutboxWriter outboxWriter;
    private final MinioAssetVerifier assetVerifier;
    private final DurableRevocationWriter revocationWriter;
    private final TransactionTemplate transactions;

    public VideoService(
            VideoJpaRepository repository,
            SupersededAssetJpaRepository supersededAssetRepository,
            OutboxWriter outboxWriter,
            MinioAssetVerifier assetVerifier,
            DurableRevocationWriter revocationWriter,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.supersededAssetRepository = supersededAssetRepository;
        this.outboxWriter = outboxWriter;
        this.assetVerifier = assetVerifier;
        this.revocationWriter = revocationWriter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Called by the Upload module inside its own transaction (brief section 7.1). No event here: nothing consumes "drafted" yet. */
    @Override
    @Transactional
    public VideoDraft createDraft(String ownerAccountId) {
        VideoEntity video = new VideoEntity(UUID.randomUUID(), UUID.fromString(ownerAccountId));
        VideoEntity saved = repository.saveAndFlush(video);
        return new VideoDraft(
                saved.getVideoId().toString(),
                saved.getOwnerAccountId().toString(),
                saved.getAggregateVersion(),
                saved.getCreatedAt());
    }

    /** Called by the Upload module's expired-session reaper (brief section 7.1). No event: nothing consumed "drafted" either. */
    @Override
    @Transactional
    public void expireDraft(String videoId) {
        VideoEntity video = repository.findById(UUID.fromString(videoId)).orElse(null);
        if (video == null || !video.expireDraft()) {
            return;
        }
        repository.saveAndFlush(video);
    }

    /**
     * Consumes {@code video.upload.completed}: assigns processingVersion and
     * dispatches the transcode command in the same transaction/version bump (brief
     * section 13). Idempotent: a video already past CREATED is left untouched.
     */
    @Transactional
    void dispatchProcessing(String videoId, String sourceObjectKey) {
        VideoEntity video = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video: " + videoId));

        if (video.getProcessingState() != ProcessingState.CREATED) {
            return; // already dispatched — redelivery no-op
        }

        int version = video.dispatchProcessing(sourceObjectKey);
        VideoEntity saved = repository.saveAndFlush(video);

        String jobId = saved.getVideoId() + ":" + version;
        var payload = new MediaEvents.MediaJobCommand(
                jobId, saved.getVideoId().toString(), version, sourceObjectKey, DEFAULT_RENDITIONS);

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.MEDIA_JOB_DISPATCHED,
                1,
                AggregateTypes.VIDEO,
                saved.getVideoId().toString(),
                saved.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }

    /**
     * Consumes {@code media.results.v1} (brief section 11.1). A stale
     * processingVersion, or a video already terminal for the current version, is a
     * silent no-op — the worker's redelivered command produces exactly one durable
     * transition.
     */
    @Transactional
    void applyMediaResult(MediaEvents.MediaResultCommand result) {
        VideoEntity video = repository.findById(UUID.fromString(result.videoId())).orElse(null);
        if (video == null) {
            return;
        }
        if (video.getProcessingVersion() == null || video.getProcessingVersion() != result.processingVersion()) {
            return; // stale processingVersion — ignore
        }
        if (video.getProcessingState() != ProcessingState.TRANSCODING) {
            return; // already terminal for this version — idempotent no-op
        }

        if ("COMPLETED".equals(result.outcome()) && result.assets() != null
                && assetVerifier.verify(result.assets().masterPlaylist(), result.assets().variantPlaylists())) {
            video.markReady(
                    result.assets().masterPlaylist(),
                    result.assets().variantPlaylists(),
                    result.assets().segmentCount(),
                    result.assets().durationSeconds());
            VideoEntity saved = repository.saveAndFlush(video);
            appendReadyEvent(saved);
        } else {
            String failureClass = "COMPLETED".equals(result.outcome()) ? "TRANSIENT" : result.failureClass();
            video.markFailed(failureClass == null ? "TERMINAL" : failureClass);
            VideoEntity saved = repository.saveAndFlush(video);
            appendFailedEvent(saved);
        }
    }

    /** Reacts to {@code video.moderation.rejected} (brief section 18, Milestone 6). */
    @Transactional
    void retainAsRejected(String videoId) {
        VideoEntity video = repository.findById(UUID.fromString(videoId)).orElse(null);
        if (video == null || !video.retainAsRejected()) {
            return; // draft not created yet, or already retained — tolerate out-of-order delivery
        }
        appendLifecycleSnapshotEvent(repository.saveAndFlush(video));
    }

    /**
     * Reacts to {@code video.moderation.reinstated} (brief section 18: "asset
     * lifecycle returns to ACTIVE if valid"). Also used directly by the admin
     * un-quarantine action, which shares the same "restore only if still valid"
     * semantics.
     */
    void restoreIfValid(String videoId) {
        VideoEntity video = repository.findById(UUID.fromString(videoId)).orElse(null);
        if (video == null) {
            return;
        }
        // Outside the transaction: verify() stats every asset in MinIO, and holding
        // a pooled connection across that network I/O is what turns a slow object
        // store into connection-pool exhaustion. See restoreFromQuarantine.
        boolean assetsValid = assetVerifier.verify(video.getMasterPlaylistKey(), video.getVariantPlaylists());

        transactions.executeWithoutResult(status -> {
            VideoEntity fresh = repository.findById(UUID.fromString(videoId)).orElse(null);
            if (fresh == null || !fresh.restoreIfValid(assetsValid)) {
                return;
            }
            appendLifecycleSnapshotEvent(repository.saveAndFlush(fresh));
        });
    }

    /** Admin lifecycle hold, independent of moderation (brief section 18, Milestone 6). */
    @Transactional
    public void quarantine(String videoId, String reason) {
        VideoEntity video = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));
        if (!video.quarantine()) {
            return;
        }
        VideoEntity saved = repository.saveAndFlush(video);
        appendLifecycleSnapshotEvent(saved);
        revocationWriter.activate(new RevocationCommand(
                RevocationSubjects.VIDEO, saved.getVideoId().toString(), LIFECYCLE_SOURCE, saved.getAggregateVersion(), reason));
    }

    /**
     * Reverses an admin quarantine, only if the assets are still verifiably present.
     *
     * <p>The MinIO check runs before the transaction opens: {@code verify()} stats
     * the master playlist and every variant, so performing it with a connection
     * checked out pins that connection across several round trips to a system that
     * can be slow or down. Re-reading the entity inside the transaction keeps the
     * write itself guarded by optimistic locking, so the brief gap between checking
     * the assets and applying the decision cannot produce a lost update.
     */
    public void restoreFromQuarantine(String videoId) {
        VideoEntity video = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));
        boolean assetsValid = assetVerifier.verify(video.getMasterPlaylistKey(), video.getVariantPlaylists());

        transactions.executeWithoutResult(status -> {
            VideoEntity fresh = repository
                    .findById(UUID.fromString(videoId))
                    .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));
            long previousVersion = fresh.getAggregateVersion();
            if (!fresh.restoreIfValid(assetsValid)) {
                throw new VideoExceptions.VideoNotReady("Assets are not verifiably present; cannot restore");
            }
            VideoEntity saved = repository.saveAndFlush(fresh);
            appendLifecycleSnapshotEvent(saved);
            revocationWriter.clear(new RevocationClearCommand(
                    RevocationSubjects.VIDEO, saved.getVideoId().toString(), LIFECYCLE_SOURCE, previousVersion));
        });
    }

    /** Admin takedown (brief section 18 "remove video"): schedules the current version's assets for deletion. */
    @Transactional
    public void remove(String videoId, String reason) {
        VideoEntity video = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));
        if (!video.scheduleForDeletion()) {
            return;
        }
        VideoEntity saved = repository.saveAndFlush(video);
        appendLifecycleSnapshotEvent(saved);
        revocationWriter.activate(new RevocationCommand(
                RevocationSubjects.VIDEO, saved.getVideoId().toString(), LIFECYCLE_SOURCE, saved.getAggregateVersion(), reason));
        if (saved.getProcessingVersion() != null) {
            supersededAssetRepository.saveAndFlush(new SupersededAssetEntity(
                    saved.getVideoId(), saved.getProcessingVersion(), saved.getMasterPlaylistKey(), saved.getVariantPlaylists()));
        }
    }

    /**
     * Admin-triggered redispatch of an already-processed video (brief section
     * 7.1 "reprocessing"). The previous version's assets are left servable until
     * this transaction commits, then handed to the cleanup job under their own
     * superseded-asset row — never deleted synchronously on the request path.
     */
    @Transactional
    public void reprocess(String videoId) {
        VideoEntity video = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));
        if (video.getSourceObjectKey() == null) {
            throw new VideoExceptions.VideoNotReady("No source object to reprocess from");
        }
        if (video.getProcessingState() == ProcessingState.TRANSCODING) {
            return; // a job is already in flight — redelivery/duplicate-click no-op
        }

        Integer previousVersion = video.getProcessingVersion();
        String previousMaster = video.getMasterPlaylistKey();
        List<String> previousVariants = video.getVariantPlaylists();

        int version = video.dispatchReprocessing(video.getSourceObjectKey());
        VideoEntity saved = repository.saveAndFlush(video);

        if (previousVersion != null && previousMaster != null) {
            supersededAssetRepository.saveAndFlush(
                    new SupersededAssetEntity(saved.getVideoId(), previousVersion, previousMaster, previousVariants));
        }

        String jobId = saved.getVideoId() + ":" + version;
        var payload = new MediaEvents.MediaJobCommand(
                jobId, saved.getVideoId().toString(), version, saved.getSourceObjectKey(), DEFAULT_RENDITIONS);
        append(saved, EventTypes.MEDIA_JOB_DISPATCHED, payload);
    }

    @Transactional(readOnly = true)
    public VideoView findForPolling(String videoId, String callerAccountId) {
        VideoEntity video = repository
                .findById(parseId(videoId))
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));

        // Owner-only (brief section 12.3): processing status is not a public
        // read model. Public discovery is the Feed module's job (Milestone 4).
        if (!video.getOwnerAccountId().toString().equals(callerAccountId)) {
            throw new VideoExceptions.VideoNotFound("No such video");
        }
        return toView(video);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VideoPlaybackView> findForPlayback(String videoId) {
        UUID id;
        try {
            id = UUID.fromString(videoId);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return repository.findById(id).map(video -> new VideoPlaybackView(
                video.getVideoId().toString(),
                video.getOwnerAccountId().toString(),
                video.getProcessingState(),
                video.getProcessingVersion(),
                video.getDurabilityState(),
                video.getAssetLifecycleState(),
                video.getLegalServingState(),
                video.getAggregateVersion()));
    }

    private void appendReadyEvent(VideoEntity video) {
        var payload = new VideoEvents.VideoProcessingReady(
                video.getVideoId().toString(),
                video.getOwnerAccountId().toString(),
                video.getProcessingVersion(),
                video.getAggregateVersion(),
                video.getMasterPlaylistKey(),
                video.getVariantPlaylists(),
                video.getSegmentCount() == null ? 0 : video.getSegmentCount(),
                video.getDurationSeconds() == null ? 0.0 : video.getDurationSeconds(),
                video.getAssetLifecycleState(),
                video.getLegalServingState());
        append(video, EventTypes.VIDEO_PROCESSING_READY, payload);
    }

    /**
     * Broadcasts an assetLifecycleState-only change (retain/restore/quarantine/
     * remove — Milestone 6). Deliberately a distinct event type from {@link
     * #appendReadyEvent}: a consumer that treats VIDEO_PROCESSING_READY as "a
     * transcode job just completed" (the Publication coordinator does) must not
     * see it here, or it will reevaluate publication state on a signal that says
     * nothing about processing.
     */
    private void appendLifecycleSnapshotEvent(VideoEntity video) {
        var payload = new VideoEvents.VideoLifecycleChanged(
                video.getVideoId().toString(),
                video.getOwnerAccountId().toString(),
                video.getAssetLifecycleState().name(),
                video.getAggregateVersion());
        append(video, EventTypes.VIDEO_LIFECYCLE_CHANGED, payload);
    }

    private void appendFailedEvent(VideoEntity video) {
        var payload = new VideoEvents.VideoProcessingFailed(
                video.getVideoId().toString(),
                video.getOwnerAccountId().toString(),
                video.getProcessingVersion(),
                video.getAggregateVersion(),
                video.getFailureClass());
        append(video, EventTypes.VIDEO_PROCESSING_FAILED, payload);
    }

    private void append(VideoEntity video, String eventType, Object payload) {
        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                AggregateTypes.VIDEO,
                video.getVideoId().toString(),
                video.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }

    private static VideoView toView(VideoEntity video) {
        return new VideoView(
                video.getVideoId().toString(),
                video.getOwnerAccountId().toString(),
                video.getProcessingState(),
                video.getProcessingVersion(),
                video.getDurabilityState(),
                video.getAssetLifecycleState(),
                video.getFailureClass(),
                video.getAggregateVersion(),
                video.getCreatedAt());
    }

    private static UUID parseId(String videoId) {
        try {
            return UUID.fromString(videoId);
        } catch (IllegalArgumentException e) {
            throw new VideoExceptions.VideoNotFound("No such video");
        }
    }
}
