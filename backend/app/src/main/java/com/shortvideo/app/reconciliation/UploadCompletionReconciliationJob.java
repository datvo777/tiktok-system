package com.shortvideo.app.reconciliation;

import com.shortvideo.moderation.api.ModerationBackfill;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.publication.api.PublicationBackfill;
import com.shortvideo.publication.api.PublicationDirectory;
import com.shortvideo.upload.api.UploadDirectory;
import com.shortvideo.upload.api.UploadDirectory.CompletedUploadView;
import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import com.shortvideo.video.api.VideoProcessingDispatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Repairs the fan-out of {@code video.upload.completed}. Three independent consumers (video,
 * moderation, publication) each react to it, and a consumer that exhausts its retries sends the
 * record to the DLT, where nothing replays it. A COMPLETED upload would then leave a video stuck
 * in CREATED, or with no moderation record, or with no publication draft, forever: the eligibility
 * reconciliation cannot see it, since it only revisits videos it already tracks.
 *
 * <p>Re-runs the same idempotent steps the listeners run, for every upload that completed more
 * than {@code grace} ago (long enough for the normal path, including its retries, to have finished)
 * and still looks incomplete. Safe alongside normal traffic: each step does nothing when its
 * result already exists.
 */
@Component
public class UploadCompletionReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(UploadCompletionReconciliationJob.class);
    private static final int PAGE_SIZE = 500;

    private final UploadDirectory uploads;
    private final VideoPlaybackDirectory videos;
    private final VideoProcessingDispatcher dispatcher;
    private final ModerationDirectory moderationDirectory;
    private final ModerationBackfill moderationBackfill;
    private final PublicationDirectory publicationDirectory;
    private final PublicationBackfill publicationBackfill;
    private final Duration grace;
    private final Duration lookback;
    private final Counter repaired;
    private final Counter failed;

    /**
     * Keyset cursor into the window, advanced per sweep and reset once a page comes back short, so a
     * window larger than one page is covered over successive runs instead of re-reading its start.
     * {@code @Scheduled(fixedDelay)} never runs this concurrently with itself.
     */
    private String cursor = UploadDirectory.FIRST;

    public UploadCompletionReconciliationJob(
            UploadDirectory uploads,
            VideoPlaybackDirectory videos,
            VideoProcessingDispatcher dispatcher,
            ModerationDirectory moderationDirectory,
            ModerationBackfill moderationBackfill,
            PublicationDirectory publicationDirectory,
            PublicationBackfill publicationBackfill,
            @Value("${shortvideo.reconciliation.upload-grace:5m}") Duration grace,
            @Value("${shortvideo.reconciliation.upload-lookback:24h}") Duration lookback,
            MeterRegistry meters) {
        this.uploads = uploads;
        this.videos = videos;
        this.dispatcher = dispatcher;
        this.moderationDirectory = moderationDirectory;
        this.moderationBackfill = moderationBackfill;
        this.publicationDirectory = publicationDirectory;
        this.publicationBackfill = publicationBackfill;
        this.grace = grace;
        this.lookback = lookback;
        this.repaired = Counter.builder("upload.completion.repaired").register(meters);
        this.failed = Counter.builder("upload.completion.repair_failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.reconciliation.interval:5m}", initialDelayString = "45s")
    public void reconcile() {
        Instant now = Instant.now();
        List<CompletedUploadView> page =
                uploads.completedBetween(now.minus(lookback), now.minus(grace), cursor, PAGE_SIZE);
        for (CompletedUploadView upload : page) {
            // One bad row must not abort the sweep; the next pass tries it again.
            try {
                repair(upload);
            } catch (RuntimeException e) {
                failed.increment();
                log.warn("Could not repair completed upload {} (video {}): {}", upload.uploadId(), upload.videoId(), e.getMessage());
            }
        }
        cursor = page.size() < PAGE_SIZE ? UploadDirectory.FIRST : page.get(page.size() - 1).uploadId();
    }

    void repair(CompletedUploadView upload) {
        Optional<VideoPlaybackView> found = videos.findForPlayback(upload.videoId());
        // A removed video must not get new downstream records; the draft is gone with the removal.
        if (found.isEmpty() || found.get().assetLifecycleState() != AssetLifecycleState.ACTIVE) {
            return;
        }
        VideoPlaybackView video = found.get();

        // The draft goes first: moderation's auto-approval emits an event the draft has to be there to receive.
        if (publicationDirectory.findState(upload.videoId()).isEmpty()
                && publicationBackfill.backfillDraft(
                        upload.videoId(), upload.accountId(), video.processingState() == ProcessingState.READY)) {
            note("publication draft", upload);
        }
        if (moderationDirectory.findDecision(upload.videoId()).isEmpty()
                && moderationBackfill.createPending(upload.videoId(), upload.accountId())) {
            note("moderation record", upload);
        }
        if (video.processingState() == ProcessingState.CREATED
                && dispatcher.dispatchProcessing(upload.videoId(), upload.sourceObjectKey())) {
            note("transcode dispatch", upload);
        }
    }

    private void note(String what, CompletedUploadView upload) {
        repaired.increment();
        // A repair means a listener lost its event; worth a line each time, since it should be rare.
        log.warn("Repaired missing {} for video {} (upload {} completed {})", what, upload.videoId(), upload.uploadId(), upload.completedAt());
    }
}
