package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
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

/**
 * Fails a video that has sat in TRANSCODING for longer than a job can possibly run. The command
 * can be lost (its outbox row went DEAD, or it was dead-lettered) and a worker can die without
 * reporting; either way nothing else moves the video, and the uploader's page polls for ten
 * minutes and then gives up with no explanation.
 *
 * <p>{@code stuck-after} is measured from the video's last update, which is when its command was
 * queued, not when a worker picked it up. It therefore has to cover the longest retry wait (under
 * five minutes), the wait for a free worker slot, and one full job (15 minutes), or a healthy job
 * queued behind others would be failed while it waits. 60 minutes leaves about 40 minutes for the
 * queue; a video that waits longer than that is a capacity problem worth the alert. The video row
 * is re-read under its lock before it is failed, so a result that lands while this runs wins.
 * One that lands afterwards is ignored, and its output is purged ({@code applyMediaResult}).
 *
 * <p>A video removed while transcoding is not failed, but past the same cutoff its processed prefix
 * is purged once more and the video is closed, for the case where no result ever arrives
 * ({@link VideoService#reclaimRemovedIfStillTranscoding}).
 */
@Component
class TranscodeWatchdog {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWatchdog.class);
    private static final int SWEEP_LIMIT = 200;

    private final VideoJpaRepository videos;
    private final VideoService videoService;
    private final Duration stuckAfter;
    private final Counter timedOut;
    private final Counter removedReclaimed;

    TranscodeWatchdog(
            VideoJpaRepository videos,
            VideoService videoService,
            @Value("${shortvideo.lifecycle.transcode-stuck-after:60m}") Duration stuckAfter,
            MeterRegistry meters) {
        this.videos = videos;
        this.videoService = videoService;
        this.stuckAfter = stuckAfter;
        this.timedOut = Counter.builder("video.transcode.timed_out").register(meters);
        this.removedReclaimed = Counter.builder("video.transcode.removed_reclaimed").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.lifecycle.transcode-watchdog-interval:5m}", initialDelayString = "1m")
    public void sweep() {
        Instant cutoff = Instant.now().minus(stuckAfter);
        List<AssetLifecycleState> removed = List.of(
                AssetLifecycleState.DELETE_SCHEDULED,
                AssetLifecycleState.DELETION_IN_PROGRESS,
                AssetLifecycleState.DELETED);
        List<VideoEntity> stuck = videos.findStuckInState(
                ProcessingState.TRANSCODING, cutoff, removed, PageRequest.of(0, SWEEP_LIMIT));
        for (VideoEntity candidate : stuck) {
            // One bad row must not abort the rest; the next sweep finds it again.
            try {
                if (videoService.failIfStillTranscoding(candidate.getVideoId().toString(), cutoff)) {
                    timedOut.increment();
                    log.warn("Video {} produced no transcode result within {}; marked FAILED (TRANSIENT)",
                            candidate.getVideoId(), stuckAfter);
                }
            } catch (RuntimeException e) {
                log.warn("Could not time out transcode for video {}: {}", candidate.getVideoId(), e.getMessage());
            }
        }
        // A video removed mid-transcode is not failed (nobody is told), but its output is reclaimed
        // in case the result that would have scheduled that never arrived.
        for (VideoEntity candidate : videos.findRemovedStuckInState(
                ProcessingState.TRANSCODING, cutoff, removed, PageRequest.of(0, SWEEP_LIMIT))) {
            try {
                if (videoService.reclaimRemovedIfStillTranscoding(candidate.getVideoId().toString(), cutoff)) {
                    removedReclaimed.increment();
                    log.warn("Removed video {} was still TRANSCODING after {}; its output is scheduled for purge",
                            candidate.getVideoId(), stuckAfter);
                }
            } catch (RuntimeException e) {
                log.warn("Could not reclaim removed video {}: {}", candidate.getVideoId(), e.getMessage());
            }
        }
    }
}
