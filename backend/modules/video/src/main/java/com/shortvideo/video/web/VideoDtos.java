package com.shortvideo.video.web;

import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoSummaryPage;
import com.shortvideo.video.api.VideoSummaryView;
import com.shortvideo.video.api.VideoView;
import java.time.Instant;
import java.util.List;

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

    /** One row of {@code GET /api/v1/videos} (the caller's own video list). */
    public record VideoSummaryResponse(
            String videoId, String title, String processingState, String assetLifecycleState, Instant createdAt) {

        public static VideoSummaryResponse from(VideoSummaryView view) {
            return new VideoSummaryResponse(
                    view.videoId(),
                    view.title(),
                    view.processingState().name(),
                    view.assetLifecycleState().name(),
                    view.createdAt());
        }
    }

    /** {@code hasMore} lets a client stop instead of paging into empty results (same convention as the Feed). */
    public record VideoListResponse(int page, List<VideoSummaryResponse> items, boolean hasMore) {
        public static VideoListResponse from(int page, VideoSummaryPage summaryPage) {
            return new VideoListResponse(
                    page, summaryPage.items().stream().map(VideoSummaryResponse::from).toList(), summaryPage.hasMore());
        }
    }

    private VideoDtos() {}
}
