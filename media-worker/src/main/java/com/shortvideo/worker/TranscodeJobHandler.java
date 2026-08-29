package com.shortvideo.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.events.Topics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    private final HlsTranscoder transcoder;
    private final WorkerProperties properties;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    TranscodeJobHandler(
            MinioObjectStore store,
            HlsTranscoder transcoder,
            WorkerProperties properties,
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper) {
        this.store = store;
        this.transcoder = transcoder;
        this.properties = properties;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    void handle(MediaEvents.MediaJobCommand job, String correlationId) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("media-worker-" + safe(job.jobId()) + "-");
            Path sourceFile = workDir.resolve("source");
            store.downloadTo(job.sourceObjectKey(), sourceFile);

            if (Files.size(sourceFile) > properties.getMaxSourceBytes()) {
                report(job, "FAILED", null, "TERMINAL", correlationId);
                return;
            }

            HlsTranscoder.TranscodeOutput output;
            try {
                output = transcoder.transcode(sourceFile, workDir.resolve("output"));
            } catch (TranscodeFailedException e) {
                log.warn("Transcode failed for job {}: {}", job.jobId(), e.getMessage());
                report(job, "FAILED", null, e.failureClass(), correlationId);
                return;
            }

            String tempPrefix = "processing-temp/" + job.jobId() + "/";
            String finalPrefix = "processed/" + job.videoId() + "/" + job.processingVersion() + "/";

            stageAndPromote(output.masterPlaylist().getParent(), tempPrefix, finalPrefix);

            MediaEvents.Assets assets = new MediaEvents.Assets(
                    finalPrefix + "master.m3u8",
                    List.of(finalPrefix + output.variantRelativePath()),
                    output.segmentCount(),
                    output.durationSeconds());
            report(job, "COMPLETED", assets, null, correlationId);

            cleanupPrefix(tempPrefix);
        } catch (Exception e) {
            log.error("Transient failure handling job {}", job.jobId(), e);
            report(job, "FAILED", null, "TRANSIENT", correlationId);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
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

    private void cleanupPrefix(String prefix) {
        try {
            for (String key : store.listKeysUnder(prefix)) {
                store.delete(key);
            }
        } catch (Exception e) {
            // Best-effort: an orphaned processing-temp object is swept at startup.
            log.debug("Failed to clean up staging prefix {}: {}", prefix, e.getMessage());
        }
    }

    private void report(
            MediaEvents.MediaJobCommand job, String outcome, MediaEvents.Assets assets, String failureClass,
            String correlationId) {
        var payload = new MediaEvents.MediaResultCommand(
                job.jobId(), job.videoId(), job.processingVersion(), outcome, assets, failureClass);

        var envelope = new EventEnvelope<>(
                UUID.randomUUID(),
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
            kafka.send(Topics.MEDIA_RESULTS, job.videoId(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("Failed to publish media.results.v1 for job {}", job.jobId(), e);
        }
    }

    private List<Path> listFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort local cleanup.
                }
            });
        } catch (IOException ignored) {
            // Directory may already be gone.
        }
    }

    private String contentTypeFor(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "application/octet-stream";
    }

    private String safe(String jobId) {
        return jobId.replace(':', '-');
    }
}
