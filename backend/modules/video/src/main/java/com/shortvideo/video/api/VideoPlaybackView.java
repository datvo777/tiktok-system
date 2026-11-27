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

    /**
     * What staff may watch: the same as an owner preview, except that a video retained after a rejection is
     * still watchable, since deciding an appeal means seeing what was rejected. A quarantined or removed
     * video is not.
     */
    public boolean moderatorPreviewEligible(int requestedProcessingVersion) {
        return processingState == ProcessingState.READY
                && durabilityState == DurabilityState.DURABLE
                && (assetLifecycleState == AssetLifecycleState.ACTIVE
                        || assetLifecycleState == AssetLifecycleState.REJECTED_RETAINED)
                && currentProcessingVersion != null
                && currentProcessingVersion == requestedProcessingVersion;
    }

    public boolean ownerPreviewEligible(int requestedProcessingVersion) {
        return processingState == ProcessingState.READY
                && durabilityState == DurabilityState.DURABLE
                && assetLifecycleState == AssetLifecycleState.ACTIVE
                && currentProcessingVersion != null
                && currentProcessingVersion == requestedProcessingVersion;
    }
}
