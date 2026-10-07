package com.shortvideo.publication.domain;

import com.shortvideo.publication.api.PublicationBackfill;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.publication.api.PublicationStateView;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationService implements PublicationDirectory, PublicationBackfill {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "publication";

    private final PublicationJpaRepository repository;
    private final OutboxWriter outboxWriter;

    public PublicationService(PublicationJpaRepository repository, OutboxWriter outboxWriter) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
    }

    /** Consumed from video.upload.completed; every video gets a PRIVATE draft (brief section 8). */
    @Transactional
    public void ensureDraft(String videoId, String ownerAccountId) {
        UUID id = UUID.fromString(videoId);
        if (repository.existsById(id)) {
            return;
        }
        repository.saveAndFlush(new PublicationEntity(id, UUID.fromString(ownerAccountId)));
        // No event: PRIVATE is the safe default the eligibility projector already
        // assumes for an unseen video (Rule 9).
    }

    /**
     * Recreates a draft whose {@code video.upload.completed} never reached {@link #ensureDraft}
     * (the event was dead-lettered). The {@code video.processing.ready} that may have been lost with
     * it cannot be replayed, so a video the caller already knows is READY is marked so here.
     */
    @Override
    @Transactional
    public boolean backfillDraft(String videoId, String ownerAccountId, boolean processingReady) {
        if (repository.existsById(UUID.fromString(videoId))) {
            return false;
        }
        ensureDraft(videoId, ownerAccountId);
        if (processingReady) {
            onProcessingReady(videoId);
        }
        return true;
    }

    @Transactional
    public void onProcessingReady(String videoId) {
        apply(videoId, true, entity -> entity.setProcessingReady(true));
    }

    @Transactional
    public void onProcessingFailed(String videoId) {
        apply(videoId, true, entity -> entity.setProcessingReady(false));
    }

    @Transactional
    public void onModerationApproved(String videoId) {
        apply(videoId, true, entity -> entity.setModerationApproved(true));
    }

    @Transactional
    public void onModerationReinstated(String videoId) {
        // Reevaluates normally; the video returns to PUBLISHED only if every
        // other prerequisite still holds (brief section 18).
        apply(videoId, true, entity -> entity.setModerationApproved(true));
    }

    @Transactional
    public void onModerationRejected(String videoId) {
        apply(videoId, true, PublicationEntity::suspend);
    }

    /** Reacts to the video lifecycle snapshot's assetLifecycleState (brief section 18, Milestone 6). */
    @Transactional
    public void onAssetLifecycleChanged(String videoId, String assetLifecycleState) {
        if ("DELETE_SCHEDULED".equals(assetLifecycleState)
                || "DELETION_IN_PROGRESS".equals(assetLifecycleState)
                || "DELETED".equals(assetLifecycleState)) {
            apply(videoId, false, PublicationEntity::remove);
        }
    }

    @Transactional
    public PublicationView requestPublish(String videoId, String ownerAccountId) {
        return applyAsOwner(videoId, ownerAccountId, PublicationEntity::requestPublish);
    }

    /**
     * The owner taking their own video back out of the feed.
     *
     * <p>Publishing was previously one-way: nothing but an admin takedown could
     * remove a video from the feed, so a creator who changed their mind had to
     * ask an administrator. This is the reversible half of that — the video
     * returns to PRIVATE and can be published again without a second review.
     */
    @Transactional
    public PublicationView withdrawPublish(String videoId, String ownerAccountId) {
        return applyAsOwner(videoId, ownerAccountId, PublicationEntity::withdrawPublish);
    }

    /** Shared owner check and event append for the two owner-driven transitions. */
    private PublicationView applyAsOwner(
            String videoId, String ownerAccountId, java.util.function.Predicate<PublicationEntity> mutation) {
        PublicationEntity entity = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new PublicationExceptions.PublicationNotFound("No such video"));
        if (!entity.getOwnerAccountId().toString().equals(ownerAccountId)) {
            throw new PublicationExceptions.NotVideoOwner("Not the owner of this video");
        }
        boolean changed = mutation.test(entity);
        PublicationEntity saved = repository.saveAndFlush(entity);
        if (changed) {
            append(saved);
        }
        return new PublicationView(saved.getVideoId().toString(), saved.getState(), saved.isIntent());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublicationStateView> findState(String videoId) {
        return repository
                .findById(UUID.fromString(videoId))
                .map(e -> new PublicationStateView(
                        e.getVideoId().toString(), e.getOwnerAccountId().toString(), e.getState().name(), e.isIntent(), e.getAggregateVersion()));
    }

    /**
     * @param draftExpected whether a missing draft is a delivery problem. Processing and moderation
     *     events only exist for a video whose upload completed, so a missing draft there means
     *     {@code video.upload.completed} (a different aggregate key, so possibly another partition)
     *     has not been applied yet. Returning quietly dropped the event for good and left the video
     *     unpublishable; throwing sends it through the listener's backoff, which covers the race,
     *     and to the DLT (counted and alerted) if the draft never appears.
     */
    private void apply(String videoId, boolean draftExpected, java.util.function.Predicate<PublicationEntity> mutation) {
        PublicationEntity entity = repository.findById(UUID.fromString(videoId)).orElse(null);
        if (entity == null) {
            if (draftExpected) {
                throw new IllegalStateException("No publication draft for video " + videoId + " yet");
            }
            return;
        }
        boolean changed = mutation.test(entity);
        PublicationEntity saved = repository.saveAndFlush(entity);
        if (changed) {
            append(saved);
        }
    }

    private void append(PublicationEntity entity) {
        String eventType = switch (entity.getState()) {
            case PUBLISHED -> EventTypes.VIDEO_PUBLICATION_PUBLISHED;
            case PUBLISH_PENDING -> EventTypes.VIDEO_PUBLICATION_PENDING;
            case SUSPENDED -> EventTypes.VIDEO_PUBLICATION_SUSPENDED;
            case PRIVATE -> EventTypes.VIDEO_PUBLICATION_PRIVATE;
            case REMOVED -> EventTypes.VIDEO_PUBLICATION_REMOVED;
        };

        var payload = new PublicationEvents.PublicationStateChanged(
                entity.getVideoId().toString(),
                entity.getOwnerAccountId().toString(),
                entity.getState(),
                entity.isIntent(),
                entity.getAggregateVersion());

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                AggregateTypes.PUBLICATION,
                entity.getVideoId().toString(),
                entity.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }
}
