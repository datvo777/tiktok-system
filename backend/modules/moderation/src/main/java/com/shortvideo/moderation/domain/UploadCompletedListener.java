package com.shortvideo.moderation.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Payloads;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes {@code video.upload.completed} to create the PENDING moderation
 * record (brief section 7.1). One of several fan-out consumers on {@code
 * video.events.v1} for this event type, alongside the Video and Publication
 * modules' own listeners.
 */
@Component
class UploadCompletedListener {

    private static final String CONSUMER = "moderation-upload-completed";
    private static final Logger log = LoggerFactory.getLogger(UploadCompletedListener.class);

    private final InboxGuard inbox;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    UploadCompletedListener(InboxGuard inbox, ModerationService moderationService, ObjectMapper objectMapper, TransactionTemplate transaction) {
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
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "moderation-upload-completed-listener")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_UPLOAD_COMPLETED.equals(envelope.eventType())) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    return;
                }
                Map<String, Object> p = envelope.payload();
                moderationService.createPending(Payloads.required(p, "videoId"), Payloads.required(p, "accountId"));
            });
        } catch (Exception e) {
            log.warn("Failed to create pending moderation record; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
