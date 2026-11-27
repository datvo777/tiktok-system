package com.shortvideo.video.web;

import com.shortvideo.eligibility.api.VideoEligibilityView;
import java.time.Instant;

public final class LifecycleDtos {

    public record ReasonRequest(String reason) {}

    /** Full status snapshot for one video, composed for the admin lookup panel. */
    public record VideoDetailResponse(
            String videoId,
            String creatorId,
            String creatorDisplayName,
            String title,
            String description,
            String processingState,
            String moderationState,
            String publicationState,
            String assetLifecycleState,
            String legalServingState,
            boolean isVideoEligible,
            Instant updatedAt) {
        public static VideoDetailResponse from(VideoEligibilityView view, String creatorDisplayName) {
            return new VideoDetailResponse(
                    view.videoId(),
                    view.creatorId(),
                    creatorDisplayName,
                    view.title(),
                    view.description(),
                    view.processingState(),
                    view.moderationState(),
                    view.publicationState(),
                    view.assetLifecycleState(),
                    view.legalServingState(),
                    view.isVideoEligible(),
                    view.updatedAt());
        }
    }

    private LifecycleDtos() {}
}
