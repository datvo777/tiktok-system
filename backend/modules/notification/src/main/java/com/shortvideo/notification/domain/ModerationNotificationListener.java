package com.shortvideo.notification.domain;

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
    private final TransactionTemplate transaction;

    ModerationNotificationListener(InboxGuard inbox, NotificationService notificationService, ObjectMapper objectMapper, TransactionTemplate transaction) {
        this.inbox = inbox;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /**
     * Not {@code @Transactional}: this topic carries every video event and each listener wants only
     * some types, so the event is decoded and matched to a handler first, and a transaction (with its
     * pooled connection and its inbox row) is opened only for an event this listener acts on.
     */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "notification-moderation-listener")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            Consumer<Map<String, Object>> handler = switch (envelope.eventType()) {
                case EventTypes.VIDEO_MODERATION_REJECTED -> p -> notificationService.create(
                        required(p, "creatorId"), "MODERATION_REJECTED",
                        "Your video was rejected" + reasonSuffix(p), required(p, "videoId"));
                case EventTypes.VIDEO_MODERATION_REINSTATED -> p -> notificationService.create(
                        required(p, "creatorId"), "MODERATION_REINSTATED",
                        "Your video was reinstated and is playable again.", required(p, "videoId"));
                case EventTypes.VIDEO_APPEAL_APPROVED -> p -> notificationService.create(
                        required(p, "creatorId"), "APPEAL_APPROVED",
                        "Your appeal was approved; your video will be reinstated.", required(p, "videoId"));
                case EventTypes.VIDEO_APPEAL_DENIED -> p -> notificationService.create(
                        required(p, "creatorId"), "APPEAL_DENIED",
                        "Your appeal was denied" + reasonSuffix(p), required(p, "videoId"));
                // Publication events carry the owner under "ownerAccountId" rather than
                // "creatorId" (PublicationEvents.PublicationStateChanged), unlike every
                // other case here.
                case EventTypes.VIDEO_PUBLICATION_PUBLISHED -> p -> notificationService.create(
                        required(p, "ownerAccountId"), "VIDEO_PUBLISHED",
                        "Your video is published and visible in the feed.", required(p, "videoId"));
                case EventTypes.VIDEO_PUBLICATION_SUSPENDED -> p -> notificationService.create(
                        required(p, "ownerAccountId"), "VIDEO_SUSPENDED",
                        "Your video was suspended and is no longer visible in the feed.", required(p, "videoId"));
                // The uploader may have left the page long before processing ends; their only
                // other signal is polling on a screen that is no longer open.
                case EventTypes.VIDEO_PROCESSING_READY -> p -> notificationService.create(
                        required(p, "ownerAccountId"), "PROCESSING_READY",
                        "Your video is ready. Preview it and publish when you\u2019re happy with it.",
                        required(p, "videoId"));
                case EventTypes.VIDEO_PROCESSING_FAILED -> p -> notificationService.create(
                        required(p, "ownerAccountId"), "PROCESSING_FAILED",
                        "TRANSIENT".equals(p.get("failureClass"))
                                ? "We couldn't process your video. Try uploading it again."
                                : "We couldn't process your video. Check that it plays locally, then try a different file.",
                        required(p, "videoId"));
                default -> null; // not relevant to notifications
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
            log.warn("Failed to apply video event to notifications; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }

    private static String required(Map<String, Object> p, String key) {
        return Payloads.required(p, key);
    }

    /** ". " is the whole message when no reason was given; otherwise the reason follows a colon. */
    private static String reasonSuffix(Map<String, Object> p) {
        Object reason = p.get("reason");
        return reason == null || reason.toString().isBlank() ? "." : ": " + reason;
    }
}
