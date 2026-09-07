package com.shortvideo.video.api;

import java.time.Instant;

/**
 * One row of the owner's own video list ({@code GET /api/v1/videos}) — enough to
 * render a badge and route into an appeal, without the processing-poll fields
 * {@link VideoView} carries for a single video in flight.
 */
public record VideoSummaryView(
        String videoId, String title, ProcessingState processingState, AssetLifecycleState assetLifecycleState, Instant createdAt) {}
