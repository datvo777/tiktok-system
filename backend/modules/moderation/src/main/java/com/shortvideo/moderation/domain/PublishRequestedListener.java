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
 * Consumes {@code video.publication.pending}, which Publication emits when an owner asks to
 * publish a video that is not yet allowed to go out. That request is what puts a held video
 * in front of a reviewer: until then nobody needs to look at it. Reuses the event Publication
 * already emits rather than adding one, since "the owner wants this public" is exactly what it says.
 */
@Component
class PublishRequestedListener {

    private static final String CONSUMER = "moderation-publish-requested";
    private static final Logger log = LoggerFactory.getLogger(PublishRequestedListener.class);

    private final InboxGuard inbox;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;

    PublishRequestedListener(InboxGuard inbox, ModerationService moderationService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "moderation-publish-requested-listener")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_PUBLICATION_PENDING.equals(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            moderationService.requestReview((String) envelope.payload().get("videoId"));
        } catch (Exception e) {
            log.warn("Failed to queue a publish-requested video for review; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
