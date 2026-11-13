package com.shortvideo.worker;

import java.io.IOException;

/**
 * One stage of a transcode job.
 *
 * <p>The job used to be a single method that probed, encoded, extracted a still
 * and wrote a manifest in sequence. That was fine while it did one thing; it
 * stopped being fine as soon as it did four, because everything the pipeline is
 * likely to grow next — a captions pass, loudness normalisation, a second
 * packaging format — arrives as more flags on the same command and more branches
 * in the same method.
 *
 * <p>Splitting it buys three things the single method could not have: a step can
 * be optional without the others knowing ({@link #required()}), a failure can be
 * attributed to a named stage instead of to "ffmpeg", and a new output is a new
 * class rather than an edit to the one everything else depends on.
 */
interface TranscodeStep {

    /** Used in logs and in the failure message, so it should read as a stage, not a class. */
    String name();

    /**
     * Whether the job fails when this step does.
     *
     * <p>Poster extraction is the reason this exists: a video that processed
     * correctly but produced no thumbnail is a far better outcome than one failed
     * over a still frame.
     */
    default boolean required() {
        return true;
    }

    void run(TranscodeContext context) throws IOException;
}
