package com.shortvideo.publication.domain;

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

    PublicationEventListener(InboxGuard inbox, PublicationService publicationService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.publicationService = publicationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "publication-coordinator")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            String videoId = (String) p.get("videoId");
            switch (envelope.eventType()) {
                case EventTypes.VIDEO_UPLOAD_COMPLETED ->
                        publicationService.ensureDraft(videoId, (String) p.get("accountId"));
                case EventTypes.VIDEO_PROCESSING_READY -> publicationService.onProcessingReady(videoId);
                case EventTypes.VIDEO_PROCESSING_FAILED -> publicationService.onProcessingFailed(videoId);
                case EventTypes.VIDEO_LIFECYCLE_CHANGED ->
                        publicationService.onAssetLifecycleChanged(videoId, (String) p.get("assetLifecycleState"));
                case EventTypes.VIDEO_MODERATION_APPROVED -> publicationService.onModerationApproved(videoId);
                case EventTypes.VIDEO_MODERATION_REJECTED -> publicationService.onModerationRejected(videoId);
                case EventTypes.VIDEO_MODERATION_REINSTATED -> publicationService.onModerationReinstated(videoId);
                default -> { /* not relevant to this coordinator */ }
            }
        } catch (Exception e) {
            log.warn("Failed to apply video event to publication coordinator; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
