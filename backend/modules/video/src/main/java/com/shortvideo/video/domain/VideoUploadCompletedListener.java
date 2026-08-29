package com.shortvideo.video.domain;

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
 * Consumes {@code video.upload.completed} from the Upload module (brief section
 * 13). {@code video.events.v1} also carries this module's own processing events
 * and, from Milestone 3, moderation/publication events — anything that is not an
 * upload-completion is ignored here, not an error.
 */
@Component
class VideoUploadCompletedListener {

    private static final String CONSUMER = "video-upload-completed";
    private static final Logger log = LoggerFactory.getLogger(VideoUploadCompletedListener.class);

    private final InboxGuard inbox;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;

    VideoUploadCompletedListener(InboxGuard inbox, VideoService videoService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "video-upload-completed-listener")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.VIDEO_UPLOAD_COMPLETED.equals(envelope.eventType())) {
                return;
            }
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            videoService.dispatchProcessing((String) p.get("videoId"), (String) p.get("sourceObjectKey"));
        } catch (Exception e) {
            log.warn("Failed to apply video.upload.completed; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
