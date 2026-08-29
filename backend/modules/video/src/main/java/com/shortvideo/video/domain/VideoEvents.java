package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.LegalServingState;
import java.util.List;

/**
 * Canonical absolute-state payloads (brief section 10). Each carries the complete
 * resulting state, including the fields the eligibility projector needs, so a
 * consumer can apply it without a synchronous callback into this module.
 */
public final class VideoEvents {

    public record VideoProcessingReady(
            String videoId,
            String ownerAccountId,
            int processingVersion,
            long aggregateVersion,
            String masterPlaylist,
            List<String> variantPlaylists,
            int segmentCount,
            double durationSeconds,
            AssetLifecycleState assetLifecycleState,
            LegalServingState legalServingState) {}

    public record VideoProcessingFailed(
            String videoId,
            String ownerAccountId,
            int processingVersion,
            long aggregateVersion,
            String failureClass) {}

    /**
     * assetLifecycleState changed independently of a processing outcome
     * (Milestone 6: retain-on-rejection, restore, quarantine, remove). Carries
     * only the fields consumers of {@link EventTypes#VIDEO_LIFECYCLE_CHANGED}
     * need — deliberately not the full processing-group snapshot, so a listener
     * cannot mistake this for "processing just completed."
     */
    public record VideoLifecycleChanged(
            String videoId, String ownerAccountId, String assetLifecycleState, long aggregateVersion) {}

    private VideoEvents() {}
}
