package com.shortvideo.social.domain;

/**
 * Canonical fact payloads (brief section 10) for the Social module's own
 * topic. Likes/comments/follows are append-only facts, not an evolving state
 * machine, so {@code aggregateVersion} here has no replay-guard purpose the
 * way it does for video/moderation/publication — it is populated only for
 * envelope-schema consistency (a wall-clock-derived value is fine since
 * nothing compares it against a prior version).
 */
public final class SocialEvents {

    public record VideoCommented(String videoId, String commenterId, String videoOwnerId, String commentId) {}

    /**
     * {@code parentAuthorId} is the person to notify — the author of the comment
     * being replied to. {@code videoOwnerId} rides along so a consumer can still
     * tell whose video the thread sits under without a second lookup.
     */
    public record CommentReplied(
            String videoId,
            String replierId,
            String parentAuthorId,
            String videoOwnerId,
            String parentCommentId,
            String commentId) {}

    public record VideoLiked(String videoId, String likerId, String videoOwnerId) {}

    public record CreatorFollowed(String followerId, String followeeId) {}

    /**
     * What one action did to a video's like and live-comment totals. A delta, not
     * a total, so applying it needs no read of the current value and two of them
     * commute: the order they arrive in does not change where the count ends up.
     */
    public record EngagementChanged(String videoId, int likeDelta, int commentDelta) {}

    private SocialEvents() {}
}
