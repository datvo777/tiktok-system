package com.shortvideo.app.reconciliation;

import com.shortvideo.moderation.api.ModerationDecisionView;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationBackfill;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs a video stuck in PUBLISH_PENDING or SUSPENDED because a processing or moderation event
 * was dead-lettered after its draft existed. The upload sweep only recreates a missing draft, and
 * the eligibility sweep recomputes a projection from the publication row, so neither touches the
 * flags inside that row; with the event gone and nothing replaying a DLT, the video would wait on
 * an outcome that already happened.
 *
 * <p>Only videos waiting on a prerequisite or held by a rejection are swept, so the sweep stays
 * small. A lost rejection on a PUBLISHED video is not caught here: the durable revocation already
 * stops playback, and the eligibility sweep covers the projection.
 */
@Component
public class PublicationFlagReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PublicationFlagReconciliationJob.class);
    private static final int PAGE_SIZE = 500;

    private final PublicationDirectory publicationDirectory;
    private final PublicationBackfill publicationBackfill;
    private final ModerationDirectory moderationDirectory;
    private final VideoPlaybackDirectory videoDirectory;
    private final Counter repaired;
    private final Counter failed;

    /** Keyset cursor, advanced per sweep and reset once a page comes back short. */
    private String cursor = null;

    public PublicationFlagReconciliationJob(
            PublicationDirectory publicationDirectory,
            PublicationBackfill publicationBackfill,
            ModerationDirectory moderationDirectory,
            VideoPlaybackDirectory videoDirectory,
            MeterRegistry meters) {
        this.publicationDirectory = publicationDirectory;
        this.publicationBackfill = publicationBackfill;
        this.moderationDirectory = moderationDirectory;
        this.videoDirectory = videoDirectory;
        this.repaired = Counter.builder("publication.flags.repaired").register(meters);
        this.failed = Counter.builder("publication.flags.repair_failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.reconciliation.interval:5m}", initialDelayString = "105s")
    @SchedulerLock(name = "publicationFlagReconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        List<String> page = publicationDirectory.findUnsettledPage(cursor, PAGE_SIZE);
        for (String videoId : page) {
            // One bad row must not abort the sweep; the next pass tries it again.
            try {
                repair(videoId);
            } catch (RuntimeException e) {
                failed.increment();
                log.warn("Could not reconcile publication flags of video {}: {}", videoId, e.getMessage());
            }
        }
        cursor = page.size() < PAGE_SIZE ? null : page.get(page.size() - 1);
    }

    void repair(String videoId) {
        boolean ready = videoDirectory
                .findForPlayback(videoId)
                .map(v -> v.processingState() == ProcessingState.READY)
                .orElse(false);
        ModerationDecisionView decision = moderationDirectory.findDecision(videoId).orElse(null);
        String moderationState = decision == null ? null : decision.state();
        long moderationVersion = decision == null ? 0 : decision.aggregateVersion();

        if (publicationBackfill.reconcileFlags(videoId, ready, moderationState, moderationVersion)) {
            repaired.increment();
            // A repair means an event never arrived; worth a line each time, since it should be rare.
            log.warn("Repaired publication state of video {}: a processing or moderation event was lost", videoId);
        }
    }
}
