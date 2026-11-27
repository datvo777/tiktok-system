package com.shortvideo.social.domain;

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
 * Applies each {@code social.video.engagement.changed} delta to {@code social.video_engagement},
 * the table a feed ranking reads its like and comment counts from.
 *
 * <p>Exactly-once in effect: a delta is added to the stored total, which is not idempotent, so
 * the event is claimed in the inbox in the same transaction as the update. A redelivered event
 * finds its claim and changes nothing. Events that are not this one are passed over without a
 * claim; this consumer shares the topic with every other social event and would otherwise write
 * an inbox row for each of them.
 *
 * <p>No version comparison (Rule 13): the payload is a delta, not a state, so there is no
 * newer state to protect from an older one. Deltas commute, and the stored total may dip below
 * zero for an instant if an unlike lands before its like; readers clamp it.
 */
@Component
public class EngagementCountListener {

    static final String CONSUMER = "social-engagement-counter";
    private static final Logger log = LoggerFactory.getLogger(EngagementCountListener.class);

    private final InboxGuard inbox;
    private final SocialRepository repository;
    private final ObjectMapper objectMapper;

    EngagementCountListener(InboxGuard inbox, SocialRepository repository, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    // Same partitioning reasoning as the notification listener on this topic: it carries the
    // platform's highest-volume events. Events are keyed by a fresh aggregate id each, so two
    // threads can hold deltas for the same video; the upsert takes the row lock briefly and the
    // deltas add in either order.
    @KafkaListener(topics = Topics.SOCIAL_EVENTS, groupId = CONSUMER, concurrency = "3")
    @Transactional
    public void onSocialEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED.equals(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            SocialEvents.EngagementChanged change =
                    EnvelopeCodec.payloadAs(objectMapper, envelope, SocialEvents.EngagementChanged.class);
            repository.applyEngagementDelta(change.videoId(), change.likeDelta(), change.commentDelta());
        } catch (Exception e) {
            log.warn("Failed to apply engagement delta; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
