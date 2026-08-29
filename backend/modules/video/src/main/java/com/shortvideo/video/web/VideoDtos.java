package com.shortvideo.video.web;

import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoView;
import java.time.Instant;

public final class VideoDtos {

    /** Brief section 12.3: the client polls this until READY or FAILED. */
    public record VideoResponse(
            String videoId,
            String processingState,
            Integer processingVersion,
            String durabilityState,
            String assetLifecycleState,
            String failureClass,
            Long pollAfterMs) {

        public static VideoResponse from(VideoView view) {
            Long pollAfterMs = switch (view.processingState()) {
                case UPLOADING, UPLOADED, TRANSCODING -> 2000L;
                default -> null;
            };
            return new VideoResponse(
                    view.videoId(),
                    view.processingState().name(),
                    view.processingVersion(),
                    view.durabilityState().name(),
                    view.assetLifecycleState().name(),
                    view.failureClass(),
                    pollAfterMs);
        }
    }

    public record PlaybackSessionResponse(String videoId, int processingVersion, String mode, Instant expiresAt) {}

    private VideoDtos() {}
}
