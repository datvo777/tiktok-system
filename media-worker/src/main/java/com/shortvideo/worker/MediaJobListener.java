package com.shortvideo.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.events.Topics;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes transcode commands (brief section 11.1). Redelivery is safe: jobId and
 * every object key this produces are deterministic, so re-running a job overwrites
 * with identical content rather than duplicating anything.
 */
@Component
public class MediaJobListener {

    private static final Logger log = LoggerFactory.getLogger(MediaJobListener.class);

    private final TranscodeJobHandler handler;
    private final ObjectMapper objectMapper;

    public MediaJobListener(TranscodeJobHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.MEDIA_JOBS, groupId = "media-worker")
    public void onJob(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!EventTypes.MEDIA_JOB_DISPATCHED.equals(envelope.eventType())) {
                return;
            }
            MediaEvents.MediaJobCommand job =
                    EnvelopeCodec.payloadAs(objectMapper, envelope, MediaEvents.MediaJobCommand.class);
            log.info("Starting transcode job {}", job.jobId());
            handler.handle(job, envelope.correlationId());
        } catch (Exception e) {
            log.error("Failed to handle transcode command", e);
        }
    }
}
