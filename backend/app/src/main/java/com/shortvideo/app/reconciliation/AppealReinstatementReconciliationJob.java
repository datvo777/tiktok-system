package com.shortvideo.app.reconciliation;

import com.shortvideo.appeal.api.AppealDirectory;
import com.shortvideo.appeal.api.AppealDirectory.ApprovedAppealView;
import com.shortvideo.moderation.api.ModerationBackfill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs an approved appeal whose {@code video.appeal.approved} event was dead-lettered before
 * moderation applied it. The creator has been told the appeal succeeded (the notification listener
 * is independent), yet the video stays rejected and nothing replays a dead-letter topic.
 *
 * <p>Safe to run against every approved appeal in the window because reinstating names the
 * rejection the appeal was filed against: a video reinstated since, or rejected again, is left
 * alone. Appeals decided less than {@link #SETTLE} ago are skipped so the normal path is not raced,
 * and those older than {@link #LOOKBACK} are not swept, which keeps the scan bounded; a repair
 * past that is a manual one.
 */
@Component
public class AppealReinstatementReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(AppealReinstatementReconciliationJob.class);
    static final int PAGE_SIZE = 500;
    static final Duration SETTLE = Duration.ofMinutes(5);
    static final Duration LOOKBACK = Duration.ofDays(7);

    private final AppealDirectory appealDirectory;
    private final ModerationBackfill moderationBackfill;
    private final Counter repaired;
    private final Counter failed;

    /** Keyset cursor, advanced per sweep and reset once a page comes back short. */
    private ApprovedAppealView cursor = null;

    public AppealReinstatementReconciliationJob(
            AppealDirectory appealDirectory, ModerationBackfill moderationBackfill, MeterRegistry meters) {
        this.appealDirectory = appealDirectory;
        this.moderationBackfill = moderationBackfill;
        this.repaired = Counter.builder("appeal.reinstatement.repaired").register(meters);
        this.failed = Counter.builder("appeal.reinstatement.repair_failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.reconciliation.interval:5m}", initialDelayString = "135s")
    @SchedulerLock(name = "appealReinstatementReconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        Instant now = Instant.now();
        List<ApprovedAppealView> page =
                appealDirectory.findApprovedPage(now.minus(LOOKBACK), now.minus(SETTLE), cursor, PAGE_SIZE);
        for (ApprovedAppealView appeal : page) {
            // One bad row must not abort the sweep; the next pass tries it again.
            try {
                repair(appeal);
            } catch (RuntimeException e) {
                failed.increment();
                log.warn("Could not reconcile the approved appeal of video {}: {}", appeal.videoId(), e.getMessage());
            }
        }
        cursor = page.size() < PAGE_SIZE ? null : page.get(page.size() - 1);
    }

    void repair(ApprovedAppealView appeal) {
        if (moderationBackfill.reinstateFromAppeal(appeal.videoId(), appeal.moderationVersion())) {
            repaired.increment();
            // A repair means the approval never reached moderation; worth a line each time, since it should be rare.
            log.warn("Reinstated video {}: its appeal was approved but the approval was never applied", appeal.videoId());
        }
    }
}
