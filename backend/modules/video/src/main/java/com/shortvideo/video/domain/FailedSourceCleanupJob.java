package com.shortvideo.video.domain;

import com.shortvideo.video.api.ProcessingState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reclaims the source of a video whose processing failed terminally and was never retried. Its
 * 500 MB original would otherwise sit under {@code sources/} for good, since nothing removes it
 * and the object-store lifecycle rule deliberately does not touch that prefix.
 *
 * <p>The retention window is what an admin has to reprocess a failure after fixing its cause;
 * once the source is scheduled for purge, {@link VideoService#reprocess} refuses. The purge itself
 * is the same queue the removal path uses, so it is retried and counted the same way.
 */
@Component
class FailedSourceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(FailedSourceCleanupJob.class);
    private static final int SWEEP_LIMIT = 200;
    private static final String TERMINAL = "TERMINAL";

    private final VideoJpaRepository videos;
    private final SupersededAssetJpaRepository purgeQueue;
    private final TransactionTemplate transaction;
    private final Duration retention;
    private final Counter scheduledCounter;

    FailedSourceCleanupJob(
            VideoJpaRepository videos,
            SupersededAssetJpaRepository purgeQueue,
            TransactionTemplate transaction,
            @Value("${shortvideo.lifecycle.failed-source-retention:7d}") Duration retention,
            MeterRegistry meters) {
        this.videos = videos;
        this.purgeQueue = purgeQueue;
        this.transaction = transaction;
        this.retention = retention;
        this.scheduledCounter = Counter.builder("video.failed_source.scheduled").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.lifecycle.failed-source-cleanup-interval:1h}", initialDelayString = "1m")
    void sweep() {
        List<VideoEntity> due = videos.findFailedAwaitingSourcePurge(
                ProcessingState.FAILED, TERMINAL, Instant.now().minus(retention), PageRequest.of(0, SWEEP_LIMIT));
        int scheduled = 0;
        for (VideoEntity candidate : due) {
            // One bad row must not abort the rest; the next sweep finds it again.
            try {
                if (scheduleSourcePurge(candidate)) {
                    scheduled++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to schedule source purge for video {}: {}", candidate.getVideoId(), e.getMessage());
            }
        }
        scheduledCounter.increment(scheduled);
        if (scheduled > 0) {
            log.info("Scheduled {} terminally failed video source{} for purge", scheduled, scheduled == 1 ? "" : "s");
        }
    }

    /**
     * Re-reads the video under a row lock: it was selected a moment ago, and an admin may have
     * reprocessed it since. Reprocessing takes the same lock, so exactly one of the two wins and
     * the other sees the result.
     */
    boolean scheduleSourcePurge(VideoEntity candidate) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            VideoEntity video = videos.findForUpdate(candidate.getVideoId()).orElse(null);
            if (video == null
                    || video.getProcessingState() != ProcessingState.FAILED
                    || !TERMINAL.equals(video.getFailureClass())
                    || purgeQueue.existsByVideoIdAndPurgePrefixIsNotNull(video.getVideoId())) {
                return false;
            }
            purgeQueue.saveAndFlush(SupersededAssetEntity.sourceOf(video.getVideoId()));
            return true;
        }));
    }
}
