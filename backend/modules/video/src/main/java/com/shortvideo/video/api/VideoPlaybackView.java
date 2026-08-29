package com.shortvideo.video.api;

/**
 * What the media gateway (playback module) may see of a video's trusted persisted
 * state (brief section 8). Owner preview evaluates directly against these fields —
 * it does not require moderation approval or public publication.
 */
public record VideoPlaybackView(
        String videoId,
        String ownerAccountId,
        ProcessingState processingState,
        Integer currentProcessingVersion,
        DurabilityState durabilityState,
        AssetLifecycleState assetLifecycleState,
        LegalServingState legalServingState,
        long aggregateVersion) {

    public boolean ownerPreviewEligible(int requestedProcessingVersion) {
        return processingState == ProcessingState.READY
                && durabilityState == DurabilityState.DURABLE
                && assetLifecycleState == AssetLifecycleState.ACTIVE
                && currentProcessingVersion != null
                && currentProcessingVersion == requestedProcessingVersion;
    }
}
