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

/** Brief section 17: fed by account.events.v1 through this consumer's durable inbox. */
@Component
class AccountEligibilityListener {

    private static final String CONSUMER = "eligibility-account-projector";
    private static final Logger log = LoggerFactory.getLogger(AccountEligibilityListener.class);
    private static final Set<String> RELEVANT =
            Set.of(EventTypes.ACCOUNT_REGISTERED, EventTypes.ACCOUNT_SUSPENDED, EventTypes.ACCOUNT_REINSTATED);

    private final InboxGuard inbox;
    private final EligibilityProjectorService projector;
    private final ObjectMapper objectMapper;

    AccountEligibilityListener(InboxGuard inbox, EligibilityProjectorService projector, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.projector = projector;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.ACCOUNT_EVENTS, groupId = "eligibility-account-projector")
    @Transactional
    public void onAccountEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!RELEVANT.contains(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            String state = String.valueOf(p.get("state"));
            projector.applyAccountState(envelope.aggregateId(), state, envelope.aggregateVersion());
        } catch (Exception e) {
            log.warn("Failed to project account eligibility event; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
