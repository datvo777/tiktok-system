package com.shortvideo.video.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Payloads;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationSources;
import com.shortvideo.shared.revocation.RevocationSubjects;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to moderation decisions to keep {@code assetLifecycleState} in sync
 * (brief section 18, Milestone 6): a rejection retains the asset instead of
 * discarding it, and a reinstatement (from a successful appeal) restores it if
 * the underlying files are still verifiably present. Own consumer group, same
 * topic and idempotent-by-eventId pattern as every other {@code video.events.v1}
 * listener in this module.
 *
 * <p>The asset state follows the moderation block as it is now, not the event as it arrived. These
 * events carry a version but the asset transitions do not compare it, so a rejection redelivered after
 * a reinstatement (a retry, a dead-letter replay) would otherwise retain an asset that was already
 * restored, and nothing later would restore it. The block is written in the same transaction as the
 * decision, so it is never behind the event.
 */
@Component
class VideoLifecycleReactionListener {

    private static final String CONSUMER = "video-lifecycle-reaction";
    private static final Logger log = LoggerFactory.getLogger(VideoLifecycleReactionListener.class);

    private final InboxGuard inbox;
    private final DurableRevocationReader revocations;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    VideoLifecycleReactionListener(
            InboxGuard inbox,
            DurableRevocationReader revocations,
            VideoService videoService,
            ObjectMapper objectMapper,
            TransactionTemplate transaction) {
        this.inbox = inbox;
        this.revocations = revocations;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /**
     * Not {@code @Transactional}: this topic carries every video event and each listener wants only
     * some types, so the event is decoded and matched to a handler first, and a transaction (with its
     * pooled connection and its inbox row) is opened only for an event this listener acts on.
     */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "video-lifecycle-reaction-listener")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            Consumer<String> handler = switch (envelope.eventType()) {
                case EventTypes.VIDEO_MODERATION_REJECTED -> videoId -> {
                    if (moderationBlocks(videoId)) {
                        videoService.retainAsRejected(videoId);
                    }
                };
                case EventTypes.VIDEO_MODERATION_REINSTATED -> videoId -> {
                    if (!moderationBlocks(videoId)) {
                        videoService.restoreAfterReinstatement(videoId);
                    }
                };
                default -> null; // not relevant to the lifecycle reaction
            };
            if (handler == null) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    return;
                }
                handler.accept(Payloads.required(envelope.payload(), "videoId"));
            });
        } catch (Exception e) {
            log.warn("Failed to apply video event to the lifecycle reaction listener; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }

    private boolean moderationBlocks(String videoId) {
        return revocations.activeSources(RevocationSubjects.VIDEO, videoId).contains(RevocationSources.MODERATION);
    }
}
