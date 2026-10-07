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
 * <p>{@code stuck-after} has to exceed the worker's job timeout plus any wait for a free worker
 * slot, or a healthy job would be failed under the worker. The video row is re-read under its lock
 * before it is failed, so a result that lands while this runs wins.
 */
@Component
class TranscodeWatchdog {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWatchdog.class);
    private static final int SWEEP_LIMIT = 200;

    private final VideoJpaRepository videos;
    private final VideoService videoService;
    private final Duration stuckAfter;
    private final Counter timedOut;

    TranscodeWatchdog(
            VideoJpaRepository videos,
            VideoService videoService,
            @Value("${shortvideo.lifecycle.transcode-stuck-after:30m}") Duration stuckAfter,
            MeterRegistry meters) {
        this.videos = videos;
        this.videoService = videoService;
        this.stuckAfter = stuckAfter;
        this.timedOut = Counter.builder("video.transcode.timed_out").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.lifecycle.transcode-watchdog-interval:5m}", initialDelayString = "1m")
    public void sweep() {
        Instant cutoff = Instant.now().minus(stuckAfter);
        List<VideoEntity> stuck = videos.findStuckInState(
                ProcessingState.TRANSCODING,
                cutoff,
                List.of(
                        AssetLifecycleState.DELETE_SCHEDULED,
                        AssetLifecycleState.DELETION_IN_PROGRESS,
                        AssetLifecycleState.DELETED),
                PageRequest.of(0, SWEEP_LIMIT));
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
    }
}
