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
 * Reacts to an approved appeal by reinstating moderation, exactly as the admin
 * "approve" action does for a REJECTED video (brief section 18, Milestone 6).
 * The Appeal module owns appeal state; this module owns moderation state and
 * only ever reacts to the canonical decision event, never calls back into
 * Appeal synchronously.
 */
@Component
class AppealDecisionListener {

    private static final String CONSUMER = "moderation-appeal-decision";
    private static final Logger log = LoggerFactory.getLogger(AppealDecisionListener.class);

    private final InboxGuard inbox;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;

    AppealDecisionListener(InboxGuard inbox, ModerationService moderationService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "moderation-appeal-decision-listener")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_APPEAL_APPROVED.equals(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            moderationService.reinstateFromAppeal((String) p.get("videoId"));
        } catch (Exception e) {
            log.warn("Failed to apply video.appeal.approved; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
