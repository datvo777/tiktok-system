package com.shortvideo.moderation.web;

import com.shortvideo.moderation.domain.ModerationView;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ModerationDtos {

    public record RejectRequest(@Size(max = 200) String reason) {}

    public record PendingVideoResponse(String videoId, String creatorId, String state, Instant createdAt) {
        public static PendingVideoResponse from(ModerationView view) {
            return new PendingVideoResponse(
                    view.videoId(), view.creatorId(), view.state().name(), view.createdAt());
        }
    }

    private ModerationDtos() {}
}
