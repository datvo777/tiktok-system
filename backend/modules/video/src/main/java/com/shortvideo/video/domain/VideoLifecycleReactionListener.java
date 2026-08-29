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
 * Reacts to moderation decisions to keep {@code assetLifecycleState} in sync
 * (brief section 18, Milestone 6): a rejection retains the asset instead of
 * discarding it, and a reinstatement (from a successful appeal) restores it if
 * the underlying files are still verifiably present. Own consumer group, same
 * topic and idempotent-by-eventId pattern as every other {@code video.events.v1}
 * listener in this module.
 */
@Component
class VideoLifecycleReactionListener {

    private static final String CONSUMER = "video-lifecycle-reaction";
    private static final Logger log = LoggerFactory.getLogger(VideoLifecycleReactionListener.class);

    private final InboxGuard inbox;
    private final VideoService videoService;
    private final ObjectMapper objectMapper;

    VideoLifecycleReactionListener(InboxGuard inbox, VideoService videoService, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "video-lifecycle-reaction-listener")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            Map<String, Object> p = envelope.payload();
            String videoId = (String) p.get("videoId");
            switch (envelope.eventType()) {
                case EventTypes.VIDEO_MODERATION_REJECTED -> videoService.retainAsRejected(videoId);
                case EventTypes.VIDEO_MODERATION_REINSTATED -> videoService.restoreIfValid(videoId);
                default -> { /* not relevant to the lifecycle reaction */ }
            }
        } catch (Exception e) {
            log.warn("Failed to apply video event to the lifecycle reaction listener; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
