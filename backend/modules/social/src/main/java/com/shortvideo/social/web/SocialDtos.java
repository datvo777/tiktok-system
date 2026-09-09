package com.shortvideo.social.web;

import com.shortvideo.social.domain.CommentView;
import com.shortvideo.social.domain.SocialService;
import com.shortvideo.social.domain.CreatorProfileView;
import com.shortvideo.social.domain.VideoCountsView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class SocialDtos {

    public record CreateCommentRequest(@NotBlank @Size(max = 500) String body) {}

    /**
     * @param watchedMs bounded so a client bug (or a tab left open overnight)
     *     cannot write an absurd total into the watch-time signal. 24 hours is
     *     far past any plausible single view of a short video.
     */
    public record RecordViewRequest(
            @Min(0) @Max(86_400_000) long watchedMs, Boolean completed) {}

    /**
     * @param authorDisplayName null on the single-comment responses returned by
     *     posting, where the caller is the author and already knows their own
     *     name. Always populated on a list, which is where a thread of UUID-
     *     derived handles was otherwise unreadable.
     */
    public record CommentResponse(
            String commentId,
            String videoId,
            String accountId,
            String authorDisplayName,
            String authorHandle,
            String body,
            Instant createdAt,
            String parentCommentId,
            long replyCount) {
        public static CommentResponse from(CommentView view) {
            return from(view, null, null);
        }

        public static CommentResponse from(CommentView view, String authorDisplayName, String authorHandle) {
            return new CommentResponse(
                    view.commentId(),
                    view.videoId(),
                    view.accountId(),
                    authorDisplayName,
                    authorHandle,
                    view.body(),
                    view.createdAt(),
                    view.parentCommentId(),
                    view.replyCount());
        }
    }

    /**
     * @param nextCursor pass back as {@code ?cursor=} for the following page;
     *     null means this was the last one. The client needs this to know the
     *     list ended rather than was truncated -- the previous unpaginated
     *     response could not tell it apart.
     */
    public record CommentListResponse(List<CommentResponse> items, String nextCursor) {
        public static CommentListResponse from(SocialService.CommentPage page) {
            return new CommentListResponse(
                    page.items().stream()
                            .map(view -> CommentResponse.from(
                                    view,
                                    page.authorNames().get(view.accountId()),
                                    page.authorHandles().get(view.accountId())))
                            .toList(),
                    page.nextCursor());
        }
    }

    public record VideoCountsResponse(
            long likeCount, long commentCount, long shareCount, long viewCount, boolean liked) {
        public static VideoCountsResponse from(VideoCountsView view) {
            return new VideoCountsResponse(
                    view.counts().likeCount(),
                    view.counts().commentCount(),
                    view.counts().shareCount(),
                    view.counts().viewCount(),
                    view.liked());
        }
    }

    /** No account state: see {@link CreatorProfileView}. */
    public record CreatorProfileResponse(
            String accountId,
            String displayName,
            String handle,
            String bio,
            long followerCount,
            long followingCount,
            boolean following) {
        public static CreatorProfileResponse from(CreatorProfileView view) {
            return new CreatorProfileResponse(
                    view.accountId(),
                    view.displayName(),
                    view.handle(),
                    view.bio(),
                    view.followerCount(),
                    view.followingCount(),
                    view.following());
        }
    }

    private SocialDtos() {}
}
