package com.shortvideo.appeal.domain;

import com.shortvideo.appeal.api.AppealState;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A creator may appeal a rejected video; an admin approves or denies the appeal
 * (brief section 18, Milestone 6). Approval does not reinstate moderation
 * directly — it publishes the canonical decision event, and the Moderation
 * module (the owner of {@code moderationState}) reacts to it, exactly as
 * account suspension and moderation rejection are owned and reacted to by their
 * respective modules elsewhere in this system.
 */
@Service
public class AppealService {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "appeal";

    private final AppealJpaRepository repository;
    private final OutboxWriter outboxWriter;
    private final VideoPlaybackDirectory videoDirectory;
    private final ModerationDirectory moderationDirectory;

    public AppealService(
            AppealJpaRepository repository,
            OutboxWriter outboxWriter,
            VideoPlaybackDirectory videoDirectory,
            ModerationDirectory moderationDirectory) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.videoDirectory = videoDirectory;
        this.moderationDirectory = moderationDirectory;
    }

    @Transactional
    public AppealView submit(String videoId, String callerAccountId, String reason) {
        VideoPlaybackView video = videoDirectory
                .findForPlayback(videoId)
                .orElseThrow(() -> new AppealExceptions.AppealNotFound("No such video"));
        if (!video.ownerAccountId().equals(callerAccountId)) {
            // Same response as a missing video: do not confirm existence to a non-owner.
            throw new AppealExceptions.AppealNotFound("No such video");
        }

        String moderationState = moderationDirectory
                .findDecision(videoId)
                .map(d -> d.state())
                .orElse("PENDING");
        if (!"REJECTED".equals(moderationState)) {
            throw new AppealExceptions.NotEligibleForAppeal("Only a rejected video may be appealed");
        }

        UUID id = UUID.fromString(videoId);
        AppealEntity entity = repository.findById(id).orElseGet(() -> new AppealEntity(id, UUID.fromString(callerAccountId)));
        if (!entity.submit(reason)) {
            throw new AppealExceptions.NotEligibleForAppeal("An appeal is already under review for this video");
        }
        AppealEntity saved = repository.saveAndFlush(entity);
        append(saved, EventTypes.VIDEO_APPEAL_SUBMITTED, reason);
        return toView(saved);
    }

    @Transactional
    public AppealView approve(String videoId, String decisionReason) {
        AppealEntity entity = find(videoId);
        if (!entity.approve(decisionReason)) {
            throw new AppealExceptions.AppealNotPending("Appeal is not awaiting a decision");
        }
        AppealEntity saved = repository.saveAndFlush(entity);
        append(saved, EventTypes.VIDEO_APPEAL_APPROVED, decisionReason);
        return toView(saved);
    }

    @Transactional
    public AppealView deny(String videoId, String decisionReason) {
        AppealEntity entity = find(videoId);
        if (!entity.deny(decisionReason)) {
            throw new AppealExceptions.AppealNotPending("Appeal is not awaiting a decision");
        }
        AppealEntity saved = repository.saveAndFlush(entity);
        append(saved, EventTypes.VIDEO_APPEAL_DENIED, decisionReason);
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public List<AppealView> listPending() {
        return repository
                .findByStateInOrderByUpdatedAtAsc(List.of(AppealState.UNDER_APPEAL, AppealState.REVIEWING))
                .stream()
                .map(AppealService::toView)
                .toList();
    }

    private AppealEntity find(String videoId) {
        return repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new AppealExceptions.AppealNotFound("No such appeal"));
    }

    private void append(AppealEntity entity, String eventType, String reason) {
        var payload = new AppealEvents.AppealDecided(
                entity.getVideoId().toString(),
                entity.getCreatorId().toString(),
                entity.getState().name(),
                entity.getAggregateVersion(),
                reason);

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                AggregateTypes.APPEAL,
                entity.getVideoId().toString(),
                entity.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }

    private static AppealView toView(AppealEntity entity) {
        return new AppealView(
                entity.getVideoId().toString(),
                entity.getCreatorId().toString(),
                entity.getState(),
                entity.getReason(),
                entity.getDecisionReason(),
                entity.getUpdatedAt());
    }
}
