package com.shortvideo.video.api;

import java.time.Instant;

/**
 * One row of the owner's own video list ({@code GET /api/v1/videos}) — enough to
 * render a badge and route into an appeal, without the processing-poll fields
 * {@link VideoView} carries for a single video in flight.
 */
public record VideoSummaryView(
        String videoId,
        String title,
        ProcessingState processingState,
        AssetLifecycleState assetLifecycleState,
        /**
         * Read from the eligibility projection, which is a derived read model —
         * so a row that has not caught up yet reports null rather than a wrong
         * answer. The client shows "Draft" for null, which is what an
         * unpublished video is.
         */
        String publicationState,
        Instant createdAt) {}
