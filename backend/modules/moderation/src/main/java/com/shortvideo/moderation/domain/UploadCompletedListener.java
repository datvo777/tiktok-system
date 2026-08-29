package com.shortvideo.moderation.domain;

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
 * Consumes {@code video.upload.completed} to create the PENDING moderation
 * record (brief section 7.1). One of several fan-out consumers on {@code
 * video.events.v1} for this event type, alongside the Video and Publication
 * modules' own listeners.
 */
@Component
class UploadCompletedListener {

    private static final String CONSUMER = "moderation-upload-completed";
    private static final Logger log = LoggerFactory.getLogger(UploadCompletedListener.class);

    private final InboxGuard inbox;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;

    UploadCompletedListener(InboxGuard inbox, ModerationService moderationService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "moderation-upload-completed-listener")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_UPLOAD_COMPLETED.equals(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            moderationService.createPending((String) p.get("videoId"), (String) p.get("accountId"));
        } catch (Exception e) {
            log.warn("Failed to create pending moderation record; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
