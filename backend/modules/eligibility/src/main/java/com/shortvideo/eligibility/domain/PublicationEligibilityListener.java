package com.shortvideo.eligibility.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Projects publication_state/publication_intent_requested, guarded by publication_version_source (brief section 17). */
@Component
class PublicationEligibilityListener {

    private static final String CONSUMER = "eligibility-publication-projector";
    private static final Logger log = LoggerFactory.getLogger(PublicationEligibilityListener.class);
    private static final Set<String> RELEVANT = Set.of(
            EventTypes.VIDEO_PUBLICATION_PUBLISHED,
            EventTypes.VIDEO_PUBLICATION_PENDING,
            EventTypes.VIDEO_PUBLICATION_SUSPENDED,
            EventTypes.VIDEO_PUBLICATION_PRIVATE);

    private final InboxGuard inbox;
    private final EligibilityProjectorService projector;
    private final ObjectMapper objectMapper;

    PublicationEligibilityListener(InboxGuard inbox, EligibilityProjectorService projector, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.projector = projector;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "eligibility-publication-projector")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!RELEVANT.contains(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            projector.applyPublication(
                    String.valueOf(p.get("videoId")),
                    String.valueOf(p.get("ownerAccountId")),
                    String.valueOf(p.get("state")),
                    Boolean.TRUE.equals(p.get("intent")),
                    envelope.aggregateVersion());
        } catch (Exception e) {
            log.warn("Failed to project publication eligibility event; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
