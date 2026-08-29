package com.shortvideo.eligibility.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects processing/durability/asset-lifecycle/legal state — the Video
 * module's own conjuncts of section 8. Moderation and publication write the
 * same row's other columns independently (see {@link ModerationEligibilityListener},
 * {@link PublicationEligibilityListener}), each guarded by its own version.
 *
 * <p>{@code video.events.v1} also carries upload/job-dispatch/moderation/
 * publication events this consumer does not care about; unrecognised event
 * types are ignored, not errors.
 */
@Component
class VideoEligibilityListener {

    private static final String CONSUMER = "eligibility-video-projector";
    private static final Logger log = LoggerFactory.getLogger(VideoEligibilityListener.class);

    private final InboxGuard inbox;
    private final EligibilityProjectorService projector;
    private final ObjectMapper objectMapper;

    VideoEligibilityListener(InboxGuard inbox, EligibilityProjectorService projector, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.projector = projector;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "eligibility-video-projector")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            switch (envelope.eventType()) {
                case EventTypes.VIDEO_PROCESSING_READY -> applyReady(envelope);
                case EventTypes.VIDEO_PROCESSING_FAILED -> applyFailed(envelope);
                case EventTypes.VIDEO_LIFECYCLE_CHANGED -> applyLifecycleChanged(envelope);
                default -> { /* not relevant to this projector */ }
            }
        } catch (Exception e) {
            log.warn("Failed to project video eligibility event; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }

    private void applyReady(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> p = envelope.payload();
        projector.applyProcessing(
                str(p, "videoId"),
                str(p, "ownerAccountId"),
                "READY",
                (Integer) p.get("processingVersion"),
                "DURABLE",
                str(p, "assetLifecycleState"),
                str(p, "legalServingState"),
                envelope.aggregateVersion());
    }

    private void applyLifecycleChanged(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> p = envelope.payload();
        projector.applyAssetLifecycle(
                str(p, "videoId"), str(p, "ownerAccountId"), str(p, "assetLifecycleState"), envelope.aggregateVersion());
    }

    private void applyFailed(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> p = envelope.payload();
        projector.applyProcessing(
                str(p, "videoId"),
                str(p, "ownerAccountId"),
                "FAILED",
                (Integer) p.get("processingVersion"),
                "PENDING",
                "ACTIVE",
                "CLEAR",
                envelope.aggregateVersion());
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }
}
