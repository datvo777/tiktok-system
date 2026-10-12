package com.shortvideo.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * State threaded through the steps of one transcode job.
 *
 * <p>Mutable and deliberately so: a pipeline of steps that each return a new
 * immutable record would need every step to know the shape of the whole job, and
 * the point of the split is that a step knows only its own inputs and outputs.
 * It is confined to one job on one thread and never escapes {@link
 * TranscodePipeline}.
 */
final class TranscodeContext {

    private final Path sourceFile;
    private final Path outputDir;
    private final long deadlineNanos;

    private HlsTranscoder.ProbedSource source;
    private final List<HlsTranscoder.Variant> variants = new ArrayList<>();
    private Path poster;
    private Path masterPlaylist;

    /**
     * @param budget the wall-clock allowance for the whole job. It is a budget for
     *     the job rather than for each ffmpeg run, because a ladder runs several
     *     of them back to back: a per-run limit would let the job take that many
     *     times longer than the limit the watchdog and the consumer's poll
     *     interval were sized against.
     */
    TranscodeContext(Path sourceFile, Path outputDir, Duration budget) {
        this.sourceFile = sourceFile;
        this.outputDir = outputDir;
        this.deadlineNanos = System.nanoTime() + budget.toNanos();
    }

    /** What is left of the job's budget; zero or negative once it has been spent. */
    Duration remainingBudget() {
        return Duration.ofNanos(deadlineNanos - System.nanoTime());
    }

    Path sourceFile() {
        return sourceFile;
    }

    Path outputDir() {
        return outputDir;
    }

    /** @throws IllegalStateException if a step asks for this before the probe ran. */
    HlsTranscoder.ProbedSource source() {
        if (source == null) {
            throw new IllegalStateException("Probe step has not run");
        }
        return source;
    }

    void setSource(HlsTranscoder.ProbedSource source) {
        this.source = source;
    }

    List<HlsTranscoder.Variant> variants() {
        return variants;
    }

    void addVariant(HlsTranscoder.Variant variant) {
        variants.add(variant);
    }

    /** Null when poster extraction was skipped or failed; that is not fatal. */
    Path poster() {
        return poster;
    }

    void setPoster(Path poster) {
        this.poster = poster;
    }

    Path masterPlaylist() {
        return masterPlaylist;
    }

    void setMasterPlaylist(Path masterPlaylist) {
        this.masterPlaylist = masterPlaylist;
    }
}
