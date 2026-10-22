package com.shortvideo.eligibility.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Payloads;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transaction;

    VideoEligibilityListener(InboxGuard inbox, EligibilityProjectorService projector, ObjectMapper objectMapper, TransactionTemplate transaction) {
        this.inbox = inbox;
        this.projector = projector;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /**
     * Not {@code @Transactional}: this topic carries every video event and each listener wants only
     * some types, so the event is decoded and matched to a handler first, and a transaction (with its
     * pooled connection and its inbox row) is opened only for an event this listener acts on.
     */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "eligibility-video-projector")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            Consumer<EventEnvelope<Map<String, Object>>> handler = switch (envelope.eventType()) {
                case EventTypes.VIDEO_METADATA_SET -> this::applyMetadata;
                case EventTypes.VIDEO_PROCESSING_READY -> this::applyReady;
                case EventTypes.VIDEO_PROCESSING_FAILED -> this::applyFailed;
                case EventTypes.VIDEO_LIFECYCLE_CHANGED -> this::applyLifecycleChanged;
                default -> null; // not relevant to this projector
            };
            if (handler == null) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    return;
                }
                handler.accept(envelope);
            });
        } catch (Exception e) {
            log.warn("Failed to project video eligibility event; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }

    private void applyMetadata(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> p = envelope.payload();
        projector.applyMetadata(
                Payloads.required(p, "videoId"), Payloads.required(p, "ownerAccountId"), str(p, "title"), str(p, "description"), envelope.aggregateVersion());
    }

    private void applyReady(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> p = envelope.payload();
        projector.applyProcessing(
                Payloads.required(p, "videoId"),
                Payloads.required(p, "ownerAccountId"),
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
                Payloads.required(p, "videoId"), Payloads.required(p, "ownerAccountId"), str(p, "assetLifecycleState"), envelope.aggregateVersion());
    }

    private void applyFailed(EventEnvelope<Map<String, Object>> envelope) {
        Map<String, Object> p = envelope.payload();
        projector.applyProcessing(
                Payloads.required(p, "videoId"),
                Payloads.required(p, "ownerAccountId"),
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
