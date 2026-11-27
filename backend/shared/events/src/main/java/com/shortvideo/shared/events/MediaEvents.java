package com.shortvideo.shared.events;

import java.util.List;

/**
 * Payloads for {@code media.jobs.v1} and {@code media.results.v1} (brief section 11.1).
 *
 * <p>Both travel inside the same {@link EventEnvelope} as every other event. The job
 * command is authoritative (dispatched from the Video module's outbox, so its
 * envelope carries an {@code aggregateVersion}); the result is not (the worker owns
 * no aggregate and sets {@code aggregateVersion} to null, per Rule 16).
 */
public final class MediaEvents {

    /**
     * {@code attempt} counts tries of the same {@code jobId} (1 for the first; a TRANSIENT failure
     * re-dispatches the same job with the next number). A command from before the field existed
     * reads as 0, which {@link #attemptNumber()} turns into 1. It lets the worker give each try's
     * result its own event id while a redelivery of one command keeps the same one.
     */
    public record MediaJobCommand(
            String jobId,
            String videoId,
            int processingVersion,
            String sourceObjectKey,
            List<String> renditions,
            int attempt) {

        public int attemptNumber() {
            return Math.max(1, attempt);
        }
    }

    /**
     * {@code failureReason} narrows a {@code failureClass} for the Video module's retry decision; it
     * is optional and absent (null) from results written before it existed. Today the one value is
     * {@link #TIMEOUT}: the job ran out of time, which a retry rarely cures when the video is simply
     * too heavy, so it is given fewer tries than a failure that was the machine's fault.
     */
    public record MediaResultCommand(
            String jobId,
            String videoId,
            int processingVersion,
            String outcome,
            Assets assets,
            String failureClass,
            String failureReason) {

        public static final String TIMEOUT = "TIMEOUT";

        /** A result with no reason. */
        public MediaResultCommand(
                String jobId, String videoId, int processingVersion, String outcome, Assets assets, String failureClass) {
            this(jobId, videoId, processingVersion, outcome, assets, failureClass, null);
        }
    }

    public record Assets(
            String masterPlaylist,
            List<String> variantPlaylists,
            int segmentCount,
            double durationSeconds) {}

    private MediaEvents() {}
}
