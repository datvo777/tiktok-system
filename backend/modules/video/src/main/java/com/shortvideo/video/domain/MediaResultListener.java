package com.shortvideo.video.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EnvelopeCodec;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.events.Topics;
import com.shortvideo.shared.inbox.InboxGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transaction;
    private final Counter duplicates;

    MediaResultListener(
            InboxGuard inbox,
            VideoService videoService,
            ObjectMapper objectMapper,
            TransactionTemplate transaction,
            MeterRegistry meters) {
        this.inbox = inbox;
        this.videoService = videoService;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
        this.duplicates = Counter.builder("video.transcode.result.duplicate").register(meters);
    }

    /**
     * Not {@code @Transactional} as a whole: the assets are checked against MinIO first, with no
     * transaction open, and only the claim and the update share one. The claim still commits (or
     * rolls back) with the business change, which is what makes a failed delivery retryable.
     */
    @KafkaListener(topics = Topics.MEDIA_RESULTS, groupId = "video-media-result-listener")
    public void onMediaResult(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            MediaEvents.MediaResultCommand result =
                    EnvelopeCodec.payloadAs(objectMapper, envelope, MediaEvents.MediaResultCommand.class);
            Boolean assetsValid = videoService.verifyAssets(result);
            transaction.executeWithoutResult(status -> {
                if (!inbox.claim(CONSUMER, envelope.eventId())) {
                    duplicates.increment();
                    return;
                }
                videoService.applyMediaResult(result, assetsValid);
            });
        } catch (Exception e) {
            log.warn("Failed to apply media.results.v1; will retry then move to DLT if still failing", e);
            throw new RuntimeException(e);
        }
    }
}
