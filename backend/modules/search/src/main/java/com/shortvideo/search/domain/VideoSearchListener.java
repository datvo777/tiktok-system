package com.shortvideo.search.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
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
 * Indexes published videos and removes ones that stop being public (brief
 * section 20, Milestone 7 acceptance: "published videos are indexed and
 * removed videos eventually disappear"). Reacts to the same publication
 * events {@link com.shortvideo.shared.events.EventTypes} already defines for
 * the eligibility projector and the publication coordinator -- no new
 * upstream event type needed.
 */
@Component
class VideoSearchListener {

    private static final String CONSUMER = "search-video-indexer";
    private static final Logger log = LoggerFactory.getLogger(VideoSearchListener.class);

    private final InboxGuard inbox;
    private final SearchIndexService indexService;
    private final AccountDirectory accountDirectory;
    private final EligibilityDirectory eligibilityDirectory;
    private final ObjectMapper objectMapper;

    VideoSearchListener(
            InboxGuard inbox,
            SearchIndexService indexService,
            AccountDirectory accountDirectory,
            EligibilityDirectory eligibilityDirectory,
            ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.indexService = indexService;
        this.accountDirectory = accountDirectory;
        this.eligibilityDirectory = eligibilityDirectory;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "search-video-indexer")
    @Transactional
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (!inbox.claim(CONSUMER, envelope.eventId())) {
                return;
            }
            if (envelope.aggregateVersion() == null) {
                return; // no version to guard the OpenSearch write with
            }
            Map<String, Object> p = envelope.payload();
            String videoId = (String) p.get("videoId");
            switch (envelope.eventType()) {
                case EventTypes.VIDEO_PUBLICATION_PUBLISHED -> {
                    String creatorId = (String) p.get("ownerAccountId");
                    var creator = accountDirectory.find(creatorId);
                    String displayName = creator.map(a -> a.displayName()).orElse("");
                    String handle = creator.map(a -> a.handle()).orElse("");
                    VideoEligibilityView eligibility = eligibilityDirectory.findVideoEligibility(videoId).orElse(null);
                    String title = eligibility == null ? null : eligibility.title();
                    String description = eligibility == null ? null : eligibility.description();
                    indexService.indexVideo(
                            videoId,
                            creatorId,
                            displayName,
                            handle,
                            title,
                            description,
                            envelope.occurredAt().toString(),
                            envelope.aggregateVersion());
                }
                case EventTypes.VIDEO_PUBLICATION_SUSPENDED,
                     EventTypes.VIDEO_PUBLICATION_PRIVATE,
                     EventTypes.VIDEO_PUBLICATION_REMOVED -> indexService.removeVideo(videoId, envelope.aggregateVersion());
                default -> { /* not relevant to search */ }
            }
        } catch (Exception e) {
            log.warn("Failed to apply video event to the search index; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }
}
