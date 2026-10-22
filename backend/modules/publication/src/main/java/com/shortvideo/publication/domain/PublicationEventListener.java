package com.shortvideo.publication.domain;

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
 * The Publication coordinator (brief section 13): consumes upload completion (to
 * create the PRIVATE draft), processing outcomes, and moderation decisions, all
 * from the same {@code video.events.v1} topic. Event types this module does not
 * care about are ignored, not errors.
 */
@Component
class PublicationEventListener {

    private static final String CONSUMER = "publication-coordinator";
    private static final Logger log = LoggerFactory.getLogger(PublicationEventListener.class);

    private final InboxGuard inbox;
    private final PublicationService publicationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    PublicationEventListener(InboxGuard inbox, PublicationService publicationService, ObjectMapper objectMapper, TransactionTemplate transaction) {
        this.inbox = inbox;
        this.publicationService = publicationService;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /**
     * Not {@code @Transactional}: this topic carries every video event and each listener wants only
     * some types, so the event is decoded and matched to a handler first, and a transaction (with its
     * pooled connection and its inbox row) is opened only for an event this listener acts on.
     */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "publication-coordinator")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            Consumer<Map<String, Object>> handler = switch (envelope.eventType()) {
                case EventTypes.VIDEO_UPLOAD_COMPLETED -> p -> publicationService.ensureDraft(
                        Payloads.required(p, "videoId"), Payloads.required(p, "accountId"));
                case EventTypes.VIDEO_PROCESSING_READY -> p ->
                        publicationService.onProcessingReady(Payloads.required(p, "videoId"));
                case EventTypes.VIDEO_PROCESSING_FAILED -> p ->
                        publicationService.onProcessingFailed(Payloads.required(p, "videoId"));
                case EventTypes.VIDEO_LIFECYCLE_CHANGED -> p -> publicationService.onAssetLifecycleChanged(
                        Payloads.required(p, "videoId"), (String) p.get("assetLifecycleState"));
                case EventTypes.VIDEO_MODERATION_APPROVED -> p ->
                        publicationService.onModerationApproved(Payloads.required(p, "videoId"));
                case EventTypes.VIDEO_MODERATION_REJECTED -> p ->
                        publicationService.onModerationRejected(Payloads.required(p, "videoId"));
                case EventTypes.VIDEO_MODERATION_REINSTATED -> p ->
                        publicationService.onModerationReinstated(Payloads.required(p, "videoId"));
                default -> null; // not relevant to this coordinator
            };
            if (handler == null) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    return;
                }
                handler.accept(envelope.payload());
            });
        } catch (Exception e) {
            log.warn("Failed to apply video event to publication coordinator; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
