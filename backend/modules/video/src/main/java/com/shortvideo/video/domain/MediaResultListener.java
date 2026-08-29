package com.shortvideo.video.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code media.results.v1} (brief section 11.1). The worker's record is
 * not authoritative (Rule 16); this module owns the resulting READY/FAILED
 * transition and the canonical outbox event.
 */
@Component
class MediaResultListener {

    private static final String CONSUMER = "video-media-result";
    private static final Logger log = LoggerFactory.getLogger(MediaResultListener.class);

    private final InboxGuard inbox;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;

    MediaResultListener(InboxGuard inbox, VideoService videoService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.MEDIA_RESULTS, groupId = "video-media-result-listener")
    @Transactional
    public void onMediaResult(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            MediaEvents.MediaResultCommand result =
                    EnvelopeCodec.payloadAs(objectMapper, envelope, MediaEvents.MediaResultCommand.class);
            videoService.applyMediaResult(result);
        } catch (Exception e) {
            log.warn("Failed to apply media.results.v1; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
