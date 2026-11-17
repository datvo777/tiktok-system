package com.shortvideo.notification.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Topics;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns {@code notification.created} into a hint on the recipient's open streams.
 *
 * <p>Each instance listens in a consumer group of its own, from the end of the topic: every
 * instance sees every event and pushes only to the streams it holds itself, so no broker
 * between instances is needed. Nothing is stored (there is no inbox guard) because a hint
 * delivered twice just causes one more refetch, and one missed while the instance was down is
 * recovered by the client reloading on reconnect.
 *
 * <p>Never throws. A failure here must not enter the retry and dead-letter path: that path exists
 * for events that must be applied, and this one is only a courtesy.
 */
@Component
@ConditionalOnProperty(prefix = "shortvideo.realtime", name = "enabled", havingValue = "true")
class RealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeListener.class);

    private final SseRegistry registry;
    private final ObjectMapper objectMapper;

    RealtimeListener(SseRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = Topics.NOTIFICATION_EVENTS,
            groupId = "realtime-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onNotificationEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.NOTIFICATION_CREATED.equals(envelope.eventType())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            String recipient = (String) p.get("recipientAccountId");
            if (recipient != null) {
                registry.publish(recipient, (String) p.get("type"), (String) p.get("relatedVideoId"), envelope.occurredAt());
            }
        } catch (Exception e) {
            log.warn("Could not push a realtime hint: {}", e.toString());
        }
    }
}
