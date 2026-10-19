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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transaction;

    PublishRequestedListener(InboxGuard inbox, ModerationService moderationService, ObjectMapper objectMapper, TransactionTemplate transaction) {
        this.inbox = inbox;
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /**
     * Not {@code @Transactional}: this topic carries every video event and each listener wants only
     * a few types, so decoding and filtering happen first and a transaction (and its pooled
     * connection) is opened only for an event this listener acts on. The inbox claim and the
     * business update share that one transaction, as the inbox requires.
     */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "moderation-publish-requested-listener")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_PUBLICATION_PENDING.equals(envelope.eventType())) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    return;
                }
                moderationService.requestReview((String) envelope.payload().get("videoId"));
            });
        } catch (Exception e) {
            log.warn("Failed to queue a publish-requested video for review; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
