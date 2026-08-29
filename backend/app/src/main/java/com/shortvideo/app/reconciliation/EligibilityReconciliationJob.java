package com.shortvideo.app.reconciliation;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityCorrector;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Milestone 5 ("Projection reconciliation"): periodically re-derives eligibility
 * from each source module's own authoritative directory and pushes the result
 * through {@link EligibilityCorrector}. That corrector reuses the same
 * version-guarded upsert normal event projection uses, so calling it with
 * already-current state is a silent no-op — this job is safe to run on a
 * schedule and safe to run concurrently with normal projection traffic.
 *
 * <p>Lives in {@code backend/app}, not the {@code eligibility} module: {@code
 * video} already depends on {@code eligibility}, so a reverse edge would be
 * circular. {@code app} depends on every module and has no such constraint.
 */
@Component
public class EligibilityReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(EligibilityReconciliationJob.class);
    private static final int SWEEP_LIMIT = 2000;

    private final EligibilityDirectory eligibilityDirectory;
    private final EligibilityCorrector corrector;
    private final VideoPlaybackDirectory videoDirectory;
    private final ModerationDirectory moderationDirectory;
    private final PublicationDirectory publicationDirectory;
    private final AccountDirectory accountDirectory;
    private final Counter videosSwept;
    private final Counter accountsSwept;

    public EligibilityReconciliationJob(
            EligibilityDirectory eligibilityDirectory,
            EligibilityCorrector corrector,
            VideoPlaybackDirectory videoDirectory,
            ModerationDirectory moderationDirectory,
            PublicationDirectory publicationDirectory,
            AccountDirectory accountDirectory,
            MeterRegistry meters) {
        this.eligibilityDirectory = eligibilityDirectory;
        this.corrector = corrector;
        this.videoDirectory = videoDirectory;
        this.moderationDirectory = moderationDirectory;
        this.publicationDirectory = publicationDirectory;
        this.accountDirectory = accountDirectory;
        this.videosSwept = Counter.builder("eligibility.reconciliation.videos_swept").register(meters);
        this.accountsSwept = Counter.builder("eligibility.reconciliation.accounts_swept").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.reconciliation.interval:5m}", initialDelayString = "30s")
    public void reconcile() {
        reconcileVideos();
        reconcileAccounts();
    }

    private void reconcileVideos() {
        for (String videoId : eligibilityDirectory.allTrackedVideoIds(SWEEP_LIMIT)) {
            try {
                videoDirectory.findForPlayback(videoId).ifPresent(v -> corrector.correctVideoProcessing(
                        v.videoId(),
                        v.ownerAccountId(),
                        v.processingState().name(),
                        v.currentProcessingVersion(),
                        v.durabilityState().name(),
                        v.assetLifecycleState().name(),
                        v.legalServingState().name(),
                        v.aggregateVersion()));

                moderationDirectory
                        .findDecision(videoId)
                        .ifPresent(m -> corrector.correctModeration(
                                m.videoId(), m.creatorId(), m.state(), m.aggregateVersion()));

                publicationDirectory
                        .findState(videoId)
                        .ifPresent(p -> corrector.correctPublication(
                                p.videoId(), p.ownerAccountId(), p.state(), p.intent(), p.aggregateVersion()));

                videosSwept.increment();
            } catch (RuntimeException e) {
                // One bad row must not abort the sweep; the next pass tries again.
                log.warn("Reconciliation failed for video {}: {}", videoId, e.getMessage());
            }
        }
    }

    private void reconcileAccounts() {
        for (String accountId : eligibilityDirectory.allTrackedAccountIds(SWEEP_LIMIT)) {
            try {
                accountDirectory
                        .find(accountId)
                        .ifPresent(a -> corrector.correctAccount(a.accountId(), a.state().name(), a.aggregateVersion()));
                accountsSwept.increment();
            } catch (RuntimeException e) {
                log.warn("Reconciliation failed for account {}: {}", accountId, e.getMessage());
            }
        }
    }
}
