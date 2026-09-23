package com.shortvideo.worker;

import java.nio.file.Path;
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

    private HlsTranscoder.ProbedSource source;
    private final List<HlsTranscoder.Variant> variants = new ArrayList<>();
    private Path poster;
    private Path masterPlaylist;

    TranscodeContext(Path sourceFile, Path outputDir) {
        this.sourceFile = sourceFile;
        this.outputDir = outputDir;
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
