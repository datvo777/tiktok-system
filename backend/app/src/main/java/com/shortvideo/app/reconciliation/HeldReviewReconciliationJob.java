package com.shortvideo.app.reconciliation;

import com.shortvideo.moderation.api.ModerationBackfill;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.publication.api.PublicationStateView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs the hand-off from "the owner asked to publish" to "a reviewer sees it". A held video
 * (PENDING, not queued) is put in the review queue by {@code video.publication.pending}; if that
 * event is dead-lettered, nothing replays it, so the owner waits on a review nobody has been asked
 * to do and nothing shows it. This sweeps the held records and queues any whose owner has asked to
 * publish.
 *
 * <p>Unlike the upload sweep there is no lookback window: the owner can ask to publish at any
 * time after upload, so a held record is a candidate for as long as it stays held. Cheap because
 * it only reads held ids (a partial index) and asks Publication about each.
 */
@Component
public class HeldReviewReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(HeldReviewReconciliationJob.class);
    private static final int PAGE_SIZE = 500;

    private final ModerationDirectory moderationDirectory;
    private final ModerationBackfill moderationBackfill;
    private final PublicationDirectory publicationDirectory;
    private final Counter repaired;
    private final Counter failed;

    /**
     * Keyset cursor into the held ids, advanced per sweep and reset once a page comes back short, so
     * a backlog larger than one page is covered over successive runs. {@code @Scheduled(fixedDelay)}
     * never runs this concurrently with itself.
     */
    private String cursor = null;

    public HeldReviewReconciliationJob(
            ModerationDirectory moderationDirectory,
            ModerationBackfill moderationBackfill,
            PublicationDirectory publicationDirectory,
            MeterRegistry meters) {
        this.moderationDirectory = moderationDirectory;
        this.moderationBackfill = moderationBackfill;
        this.publicationDirectory = publicationDirectory;
        this.repaired = Counter.builder("moderation.review.repaired").register(meters);
        this.failed = Counter.builder("moderation.review.repair_failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.reconciliation.interval:5m}", initialDelayString = "75s")
    public void reconcile() {
        List<String> held = moderationDirectory.findHeldPage(cursor, PAGE_SIZE);
        for (String videoId : held) {
            // One bad row must not abort the sweep; the next pass tries it again.
            try {
                repair(videoId);
            } catch (RuntimeException e) {
                failed.increment();
                log.warn("Could not check held video {} for a publish request: {}", videoId, e.getMessage());
            }
        }
        cursor = held.size() < PAGE_SIZE ? null : held.get(held.size() - 1);
    }

    void repair(String videoId) {
        Optional<PublicationStateView> publication = publicationDirectory.findState(videoId);
        if (publication.isPresent() && publication.get().intent() && moderationBackfill.requestReview(videoId)) {
            repaired.increment();
            // A repair means the publish event never arrived; worth a line each time, since it should be rare.
            log.warn("Queued held video {} for review: its owner asked to publish but the event never queued it", videoId);
        }
    }
}
