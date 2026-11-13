package com.shortvideo.moderation.web;

import com.shortvideo.moderation.api.PolicyCategory;
import com.shortvideo.moderation.domain.ModerationPage;
import com.shortvideo.moderation.domain.ModerationView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ModerationDtos {

    /**
     * {@code policyCategory} is required: a rejection with no classification is
     * exactly the unmeasurable record this replaced. {@code reason} stays
     * optional and is elaboration only.
     */
    public record RejectRequest(@NotNull PolicyCategory policyCategory, @Size(max = 200) String reason) {}

    /**
     * {@code createdAt} is when the upload arrived; {@code queuedAt} is when it began waiting for a
     * reviewer, and {@code queueReason} says why (UPLOAD, RISK, UNSCREENED, PUBLISH_REQUESTED).
     */
    public record PendingVideoResponse(
            String videoId, String creatorId, String state, Instant createdAt, Instant queuedAt, String queueReason) {
        public static PendingVideoResponse from(ModerationView view) {
            return new PendingVideoResponse(
                    view.videoId(),
                    view.creatorId(),
                    view.state().name(),
                    view.createdAt(),
                    view.queuedAt(),
                    view.queueReason() == null ? null : view.queueReason().name());
        }
    }

    /** {@code nextCursor} is null when this is the last page. */
    public record PendingPageResponse(List<PendingVideoResponse> items, String nextCursor) {
        public static PendingPageResponse from(ModerationPage page) {
            return new PendingPageResponse(
                    page.items().stream().map(PendingVideoResponse::from).toList(), page.nextCursor());
        }
    }

    private ModerationDtos() {}
}
