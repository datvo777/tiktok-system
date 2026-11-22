package com.shortvideo.app.reconciliation;

import com.shortvideo.social.api.EngagementReconciler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the feed's like and comment totals honest. Those totals are built from events, and an
 * event can be lost or, rarely, applied twice, so this recounts a page of videos from the source
 * tables on every run and corrects any total that disagrees. The source tables stay
 * authoritative (Rule 12); the totals only decide a ranking.
 *
 * <p>A correction counts as a repair worth a warning: it means an event did not do its job, and
 * it should be rare.
 */
@Component
public class EngagementReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(EngagementReconciliationJob.class);
    private static final int PAGE_SIZE = 500;

    /**
     * A total touched more recently than this is left for a later pass. Events are applied within
     * seconds; ten minutes also covers a relay backlog, and a video that is liked constantly is
     * never "quiet", so it waits for a lull rather than being corrected against a delta in flight.
     */
    static final Duration QUIET_FOR = Duration.ofMinutes(10);

    private final EngagementReconciler reconciler;
    private final Counter corrected;
    private final Counter checked;

    /** Resume point, per instance, reset once a sweep reaches the end of the table. */
    private String cursor = null;

    public EngagementReconciliationJob(EngagementReconciler reconciler, MeterRegistry meters) {
        this.reconciler = reconciler;
        this.corrected = Counter.builder("social.engagement.corrected").register(meters);
        this.checked = Counter.builder("social.engagement.checked").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.reconciliation.interval:5m}", initialDelayString = "90s")
    // One instance at a time: the cursor is per instance, and two sweeping would only recount the
    // same videos. lockAtLeastFor stops a quick sweep being followed at once by another instance's.
    @SchedulerLock(name = "engagementReconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        EngagementReconciler.Sweep sweep = reconciler.reconcile(cursor, PAGE_SIZE, QUIET_FOR);
        cursor = sweep.lastVideoId();
        checked.increment(sweep.checked());
        if (sweep.corrected() > 0) {
            corrected.increment(sweep.corrected());
            log.warn(
                    "Corrected {} of {} like/comment totals that had drifted from the source tables",
                    sweep.corrected(),
                    sweep.checked());
        }
    }
}
