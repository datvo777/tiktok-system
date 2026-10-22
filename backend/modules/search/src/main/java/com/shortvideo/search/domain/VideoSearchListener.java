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
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final String PUBLICATION_EVENT_PREFIX = "video.publication.";
    private static final Logger log = LoggerFactory.getLogger(VideoSearchListener.class);

    private final InboxGuard inbox;
    private final SearchIndexService indexService;
    private final AccountDirectory accountDirectory;
    private final EligibilityDirectory eligibilityDirectory;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    VideoSearchListener(
            InboxGuard inbox,
            SearchIndexService indexService,
            AccountDirectory accountDirectory,
            EligibilityDirectory eligibilityDirectory,
            ObjectMapper objectMapper,
            TransactionTemplate transaction) {
        this.inbox = inbox;
        this.indexService = indexService;
        this.accountDirectory = accountDirectory;
        this.eligibilityDirectory = eligibilityDirectory;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    /**
     * Not {@code @Transactional}: the index write is a call to OpenSearch, and holding a database
     * connection while it answers lets a slow search cluster use up the pool. The write is
     * version-guarded (an older version cannot overwrite a newer one), so doing it before the claim
     * is safe: a redelivery repeats a write that is rejected as stale, and the claim, in its own
     * short transaction, is made only once the write has not failed. A write that throws is not
     * claimed, so it is redelivered.
     */
    @KafkaListener(topics = Topics.VIDEO_EVENTS, groupId = "search-video-indexer")
    public void onVideoEvent(String payload) {
        try {
            EventEnvelope<Map<String, Object>> envelope = EnvelopeCodec.decode(objectMapper, payload);
            if (envelope.aggregateVersion() == null) {
                // No version to guard the OpenSearch write with, so nothing to apply; claimed all the
                // same, so that the same event is not looked at again.
                claim(envelope);
                return;
            }
            Map<String, Object> p = envelope.payload();
            String videoId = (String) p.get("videoId");
            String eventType = envelope.eventType();
            if (EventTypes.VIDEO_PUBLICATION_PUBLISHED.equals(eventType)) {
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
            } else if (eventType.startsWith(PUBLICATION_EVENT_PREFIX)) {
                // Allowlist, not blocklist: PUBLISHED is the only publication state that stays
                // searchable, so every other one -- and any state added later -- leaves the index
                // the same way it leaves the feed. Scoped to publication events because this topic
                // also carries other aggregates' events, whose versions are not comparable with the
                // one the index guards on.
                indexService.removeVideo(videoId, envelope.aggregateVersion());
            }
            claim(envelope);
        } catch (Exception e) {
            log.warn("Failed to apply video event to the search index; will retry on redelivery", e);
            throw new RuntimeException(e);
        }
    }

    private void claim(EventEnvelope<Map<String, Object>> envelope) {
        transaction.executeWithoutResult(status -> inbox.claim(CONSUMER, envelope.eventId()));
    }
}
