package com.shortvideo.social.web;

import com.shortvideo.social.domain.CommentView;
import com.shortvideo.social.domain.CreatorProfileView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class SocialDtos {

    public record CreateCommentRequest(@NotBlank @Size(max = 500) String body) {}

    public record CommentResponse(String commentId, String videoId, String accountId, String body, Instant createdAt) {
        public static CommentResponse from(CommentView view) {
            return new CommentResponse(view.commentId(), view.videoId(), view.accountId(), view.body(), view.createdAt());
        }
    }

    public record CommentListResponse(List<CommentResponse> items) {
        public static CommentListResponse from(List<CommentView> views) {
            return new CommentListResponse(views.stream().map(CommentResponse::from).toList());
        }
    }

    /** No account state: see {@link CreatorProfileView}. */
    public record CreatorProfileResponse(
            String accountId, String displayName, long followerCount, long followingCount) {
        public static CreatorProfileResponse from(CreatorProfileView view) {
            return new CreatorProfileResponse(
                    view.accountId(), view.displayName(), view.followerCount(), view.followingCount());
        }
    }

    private SocialDtos() {}
}
