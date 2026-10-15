package com.shortvideo.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Full job lifecycle: download the source, transcode to a local temp dir, stage
 * the result under {@code processing-temp/{jobId}/}, validate, promote to
 * {@code processed/{videoId}/{processingVersion}/}, and report the result (brief
 * section 13, section 14, Rule 16).
 *
 * <p>{@code jobId} is deterministic ({@code {videoId}:{processingVersion}}), so a
 * redelivered command re-derives the same temp and final prefixes — re-execution
 * overwrites with identical content and is safe.
 */
@Component
class TranscodeJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscodeJobHandler.class);
    private static final String PRODUCER = "media-worker";

    private final MinioObjectStore store;
    private final TranscodePipeline pipeline;
    private final WorkerProperties properties;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    TranscodeJobHandler(
            MinioObjectStore store,
            TranscodePipeline pipeline,
            WorkerProperties properties,
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            MeterRegistry meters) {
        this.meters = meters;
        this.store = store;
        this.pipeline = pipeline;
        this.properties = properties;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    void handle(MediaEvents.MediaJobCommand job, String correlationId) {
        Timer.Sample sample = Timer.start(meters);
        try {
            run(job, correlationId);
        } finally {
            sample.stop(meters.timer("worker.job.duration"));
        }
    }

    private void run(MediaEvents.MediaJobCommand job, String correlationId) {
        Path workDir = null;
        String tempPrefix = "processing-temp/" + job.jobId() + "/";
        try {
            workDir = Files.createTempDirectory("media-worker-" + safe(job.jobId()) + "-");
            Path sourceFile = workDir.resolve("source");
            store.downloadTo(job.sourceObjectKey(), sourceFile);

            if (Files.size(sourceFile) > properties.getMaxSourceBytes()) {
                report(job, "FAILED", null, "TERMINAL", null, correlationId);
                return;
            }

            HlsTranscoder.TranscodeOutput output;
            try {
                output = pipeline.run(sourceFile, workDir.resolve("output"));
            } catch (TranscodeFailedException e) {
                log.warn("Transcode failed for job {}: {}", job.jobId(), e.getMessage());
                report(job, "FAILED", null, e.failureClass(), e.reason(), correlationId);
                return;
            }

            String finalPrefix = "processed/" + job.videoId() + "/" + job.processingVersion() + "/";

            stageAndPromote(output.masterPlaylist().getParent(), tempPrefix, finalPrefix);

            MediaEvents.Assets assets = new MediaEvents.Assets(
                    finalPrefix + "master.m3u8",
                    output.variantRelativePaths().stream().map(path -> finalPrefix + path).toList(),
                    output.segmentCount(),
                    output.durationSeconds());
            report(job, "COMPLETED", assets, null, null, correlationId);
        } catch (ResultNotPublishedException e) {
            // The job itself ran; it is the report that did not get through. Reporting a failure
            // here would be a second send that can fail the same way, so let the listener's error
            // handler redeliver the command instead (deterministic output makes the rerun safe).
            throw e;
        } catch (TranscodeFailedException e) {
            // Raised outside the pipeline, e.g. a source object that does not exist.
            log.warn("Job {} failed: {}", job.jobId(), e.getMessage());
            report(job, "FAILED", null, e.failureClass(), e.reason(), correlationId);
        } catch (Exception e) {
            log.error("Transient failure handling job {}", job.jobId(), e);
            report(job, "FAILED", null, "TRANSIENT", null, correlationId);
        } finally {
            // Whatever way the job ended, its staging objects are of no further use: on success they
            // were copied to processed/, on failure they are partial, and a rerun rewrites them.
            // The local directory goes first: it can hold a 500 MB source, and a slow or unreachable
            // MinIO must not be what keeps the disk full.
            if (workDir != null) {
                LocalFiles.deleteRecursively(workDir);
            }
            cleanupStaging(tempPrefix);
        }
    }

    /** Upload every output file to the temp prefix, then server-side copy each to the final prefix. */
    private void stageAndPromote(Path outputRoot, String tempPrefix, String finalPrefix) throws Exception {
        List<Path> files = listFiles(outputRoot);
        List<String> relativeKeys = new ArrayList<>();
        for (Path file : files) {
            String relative = outputRoot.relativize(file).toString().replace('\\', '/');
            relativeKeys.add(relative);
            store.upload(tempPrefix + relative, file, contentTypeFor(relative));
        }
        for (String relative : relativeKeys) {
            store.copy(tempPrefix + relative, finalPrefix + relative);
        }
    }

    /**
     * Best-effort, and it never fails the job: the result is what matters, and a leftover object is
     * reclaimed by the sweeper and the bucket's lifecycle rule. It is not silent, though: leftovers
     * that nobody hears about pile up, so each one is logged and counted.
     */
    private void cleanupStaging(String prefix) {
        try {
            int failed = store.deleteAllUnder(prefix);
            if (failed > 0) {
                meters.counter("worker.staging.cleanup.failed").increment(failed);
                log.warn("Left {} staging object(s) under {}; the sweeper will reclaim them", failed, prefix);
            }
        } catch (Exception e) {
            // Could not even list the prefix.
            meters.counter("worker.staging.cleanup.failed").increment();
            log.warn("Could not clean up staging prefix {}; the sweeper will reclaim it: {}", prefix, e.getMessage());
        }
    }

    private void report(
            MediaEvents.MediaJobCommand job, String outcome, MediaEvents.Assets assets, String failureClass,
            String failureReason, String correlationId) {
        var payload = new MediaEvents.MediaResultCommand(
                job.jobId(), job.videoId(), job.processingVersion(), outcome, assets, failureClass, failureReason);

        var envelope = new EventEnvelope<>(
                resultEventId(job, outcome, failureClass),
                EventTypes.MEDIA_RESULT_REPORTED,
                1,
                AggregateTypes.VIDEO,
                job.videoId(),
                null,
                Instant.now(),
                PRODUCER,
                "media-worker",
                correlationId,
                null,
                payload);
        try {
            kafka.send(Topics.MEDIA_RESULTS, job.videoId(), objectMapper.writeValueAsString(envelope))
                    .get(properties.getResultAckTimeout().toMillis(), TimeUnit.MILLISECONDS);
            // Counted once the broker has the result, so a result that is redelivered and reported
            // again is not counted as two jobs' worth of failures it did not have.
            meters.counter("worker.jobs", "outcome", outcome, "failureClass", failureClass == null ? "none" : failureClass)
                    .increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultNotPublishedException(job.jobId(), e);
        } catch (Exception e) {
            log.error("Failed to publish media.results.v1 for job {}", job.jobId(), e);
            throw new ResultNotPublishedException(job.jobId(), e);
        }
    }

    /**
     * Derived from the job, its try number and the outcome, so a redelivered command reports with
     * the same id and the Video module's inbox absorbs the duplicate. The try number keeps the
     * results of two tries of the same jobId (a TRANSIENT retry) apart. The inbox is cleaned up
     * after a while, so the processingVersion check on the Video side remains the real fence.
     */
    static UUID resultEventId(MediaEvents.MediaJobCommand job, String outcome, String failureClass) {
        String key = job.jobId() + "|" + job.attemptNumber() + "|" + outcome + "|" + (failureClass == null ? "" : failureClass);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** The result could not be handed to the broker; the command should be redelivered. */
    static final class ResultNotPublishedException extends RuntimeException {
        ResultNotPublishedException(String jobId, Throwable cause) {
            super("media.results.v1 for job " + jobId + " was not acknowledged", cause);
        }
    }

    private List<Path> listFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private String contentTypeFor(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".ts")) return "video/mp2t";
        // The poster is served straight to an <img>/<video poster>, so it needs a
        // real image type -- octet-stream would make the browser refuse to render it.
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private String safe(String jobId) {
        return jobId.replace(':', '-');
    }
}
