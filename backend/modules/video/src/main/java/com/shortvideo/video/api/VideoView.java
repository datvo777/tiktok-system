package com.shortvideo.video.api;

import java.time.Instant;

/**
 * Processing-status read model for {@code GET /api/v1/videos/{videoId}} (brief
 * section 12.3). {@code pollAfterMs} is computed by the caller, not stored.
 */
public record VideoView(
        String videoId,
        String ownerAccountId,
        ProcessingState processingState,
        Integer processingVersion,
        DurabilityState durabilityState,
        AssetLifecycleState assetLifecycleState,
        String failureClass,
        long aggregateVersion,
        Instant createdAt) {}
