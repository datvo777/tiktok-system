package com.shortvideo.moderation.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Payloads;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes {@code video.lifecycle.changed} to close the moderation record of a video that was
 * removed (deleted by its owner or taken down). Without it the record stays PENDING in the review
 * queue for good, and reviewers open videos that can never play. The same lifecycle states
 * Publication treats as removal.
 */
@Component
class VideoRemovedListener {

    private static final String CONSUMER = "moderation-video-removed";
    private static final Logger log = LoggerFactory.getLogger(VideoRemovedListener.class);
    private static final Set<String> REMOVED_STATES = Set.of("DELETE_SCHEDULED", "DELETION_IN_PROGRESS", "DELETED");

    private final InboxGuard inbox;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    VideoRemovedListener(
            InboxGuard inbox,
            ModerationService moderationService,
            ObjectMapper objectMapper,
            TransactionTemplate transaction) {
        this.inbox = inbox;
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /** Not {@code @Transactional}: filtered first, so only a removal opens a transaction. */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "moderation-video-removed-listener")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_LIFECYCLE_CHANGED.equals(envelope.eventType())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            if (!REMOVED_STATES.contains((String) p.get("assetLifecycleState"))) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    return;
                }
                moderationService.closeForRemovedVideo(Payloads.required(p, "videoId"));
            });
        } catch (Exception e) {
            log.warn("Failed to close the moderation record of a removed video; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
