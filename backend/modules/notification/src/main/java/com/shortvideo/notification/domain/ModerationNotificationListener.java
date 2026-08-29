package com.shortvideo.notification.domain;

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
 * Notifies a creator of moderation/appeal outcomes on their own videos (brief
 * section 20, Milestone 7). Event types this module does not care about are
 * ignored, not errors -- the same convention every {@code video.events.v1}
 * listener in this system follows.
 */
@Component
class ModerationNotificationListener {

    private static final String CONSUMER = "notification-moderation-listener";
    private static final Logger log = LoggerFactory.getLogger(ModerationNotificationListener.class);

    private final InboxGuard inbox;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    ModerationNotificationListener(InboxGuard inbox, NotificationService notificationService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "notification-moderation-listener")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            String videoId = (String) p.get("videoId");
            String creatorId = (String) p.get("creatorId");
            String reason = (String) p.get("reason");
            switch (envelope.eventType()) {
                case EventTypes.VIDEO_MODERATION_REJECTED -> notificationService.create(
                        creatorId, "MODERATION_REJECTED",
                        "Your video was rejected" + (reason == null || reason.isBlank() ? "." : ": " + reason),
                        videoId);
                case EventTypes.VIDEO_MODERATION_REINSTATED -> notificationService.create(
                        creatorId, "MODERATION_REINSTATED", "Your video was reinstated and is playable again.", videoId);
                case EventTypes.VIDEO_APPEAL_DENIED -> notificationService.create(
                        creatorId, "APPEAL_DENIED",
                        "Your appeal was denied" + (reason == null || reason.isBlank() ? "." : ": " + reason), videoId);
                default -> { /* not relevant to notifications */ }
            }
        } catch (Exception e) {
            log.warn("Failed to apply video event to notifications; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
