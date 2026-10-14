package com.shortvideo.worker;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a transcode job as an ordered sequence of named steps.
 *
 * <p>The order is a real dependency chain rather than a convention: the ladder
 * needs the probe's dimensions, the poster needs its duration, and the manifest
 * needs the variants the ladder produced. Expressing it as a list makes that
 * order the one place it is stated.
 */
@Component
class TranscodePipeline {

    private static final Logger log = LoggerFactory.getLogger(TranscodePipeline.class);

    private final List<TranscodeStep> steps;
    private final WorkerProperties properties;
    private final MeterRegistry meters;

    TranscodePipeline(HlsTranscoder transcoder, WorkerProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
        this.steps = List.of(
                new ProbeStep(transcoder),
                new EncodeLadderStep(transcoder),
                new PosterStep(transcoder),
                new ManifestStep(transcoder));
    }

    HlsTranscoder.TranscodeOutput run(Path sourceFile, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        TranscodeContext context = new TranscodeContext(sourceFile, outputDir, properties.getJobTimeout());

        for (TranscodeStep step : steps) {
            long startedAt = System.nanoTime();
            try {
                step.run(context);
                log.debug("Transcode step '{}' finished in {}ms", step.name(), elapsedMillis(startedAt));
            } catch (TranscodeFailedException e) {
                if (step.required()) {
                    // Re-thrown with the stage named, so a failure report says
                    // which part of the job broke rather than only that ffmpeg
                    // exited non-zero.
                    throw new TranscodeFailedException(
                            e.failureClass(), "step '" + step.name() + "': " + e.getMessage());
                }
                log.warn("Optional transcode step '{}' failed; continuing: {}", step.name(), e.getMessage());
            } catch (IOException | RuntimeException e) {
                if (step.required()) {
                    throw e;
                }
                log.warn("Optional transcode step '{}' failed; continuing: {}", step.name(), e.getMessage());
            } finally {
                // Failed steps are timed too: a step that dies after ten minutes is the interesting one.
                meters.timer("worker.transcode.step.duration", "step", step.name())
                        .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            }
        }

        return assemble(context);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private HlsTranscoder.TranscodeOutput assemble(TranscodeContext context) {
        List<HlsTranscoder.Variant> variants = context.variants();
        HlsTranscoder.Variant primary = variants.get(0);
        int totalSegments = variants.stream().mapToInt(HlsTranscoder.Variant::segmentCount).sum();
        return new HlsTranscoder.TranscodeOutput(
                context.masterPlaylist(),
                primary.playlist(),
                primary.relativePlaylistPath(),
                totalSegments,
                context.source().durationSeconds(),
                variants.stream().map(HlsTranscoder.Variant::relativePlaylistPath).toList(),
                context.poster() == null ? null : HlsTranscoder.POSTER_FILENAME);
    }

    /** Establishes what the source actually is, and refuses anything unusable. */
    private record ProbeStep(HlsTranscoder transcoder) implements TranscodeStep {
        @Override
        public String name() {
            return "probe";
        }

        @Override
        public void run(TranscodeContext context) throws IOException {
            context.setSource(transcoder.probeSource(context.sourceFile()));
        }
    }

    /** Encodes every rung the source is large enough for. */
    private record EncodeLadderStep(HlsTranscoder transcoder) implements TranscodeStep {
        @Override
        public String name() {
            return "encode-ladder";
        }

        @Override
        public void run(TranscodeContext context) throws IOException {
            HlsTranscoder.ProbedSource source = context.source();
            for (HlsTranscoder.Rung rung : HlsTranscoder.ladderFor(source.width(), source.height())) {
                context.addVariant(transcoder.encodeRung(
                        context.sourceFile(), context.outputDir(), source, rung, source.durationSeconds(),
                        context.remainingBudget()));
            }
        }
    }

    /** Optional: a video without a thumbnail beats a video that failed over one. */
    private record PosterStep(HlsTranscoder transcoder) implements TranscodeStep {
        @Override
        public String name() {
            return "poster";
        }

        @Override
        public boolean required() {
            return false;
        }

        @Override
        public void run(TranscodeContext context) {
            HlsTranscoder.ProbedSource source = context.source();
            context.setPoster(transcoder.extractPoster(
                    context.sourceFile(), context.outputDir(), source, source.durationSeconds()));
        }
    }

    /** Writes the master playlist that ties the rungs together. */
    private record ManifestStep(HlsTranscoder transcoder) implements TranscodeStep {
        @Override
        public String name() {
            return "manifest";
        }

        @Override
        public void run(TranscodeContext context) throws IOException {
            context.setMasterPlaylist(transcoder.writeMasterPlaylist(
                    context.outputDir(), context.variants(), context.source().hasAudio()));
        }
    }
}
