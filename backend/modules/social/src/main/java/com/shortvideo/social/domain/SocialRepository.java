package com.shortvideo.social.domain;

import com.shortvideo.social.api.SocialCounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SocialRepository {

    private static final String LIKE = """
            INSERT INTO social.video_like (video_id, account_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (video_id, account_id) DO NOTHING
            """;

    private static final String UNLIKE = "DELETE FROM social.video_like WHERE video_id = ? AND account_id = ?";

    private static final String IS_LIKED =
            "SELECT EXISTS (SELECT 1 FROM social.video_like WHERE video_id = ? AND account_id = ?)";

    private static final String ADD_COMMENT = """
            INSERT INTO social.comment (comment_id, video_id, account_id, body, created_at, parent_comment_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** Top-level comments only, each with the count of its own replies. */
    private static final String LIST_COMMENTS = """
            SELECT c.comment_id, c.video_id, c.account_id, c.body, c.created_at,
                   (SELECT count(*) FROM social.comment r WHERE r.parent_comment_id = c.comment_id) AS reply_count
            FROM social.comment c
            WHERE c.video_id = ? AND c.parent_comment_id IS NULL
            ORDER BY c.created_at DESC
            LIMIT 200
            """;

    private static final String LIST_REPLIES = """
            SELECT comment_id, video_id, account_id, body, created_at, parent_comment_id
            FROM social.comment
            WHERE parent_comment_id = ?
            ORDER BY created_at ASC
            LIMIT 200
            """;

    private static final String IS_TOP_LEVEL_COMMENT_ON_VIDEO = """
            SELECT EXISTS (
                SELECT 1 FROM social.comment
                WHERE comment_id = ? AND video_id = ? AND parent_comment_id IS NULL
            )
            """;

    private static final String COUNTS = """
            SELECT
                (SELECT count(*) FROM social.video_like WHERE video_id = ?) AS like_count,
                (SELECT count(*) FROM social.comment WHERE video_id = ?) AS comment_count
            """;

    private static final String FOLLOW = """
            INSERT INTO social.follow (follower_id, followee_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (follower_id, followee_id) DO NOTHING
            """;

    private static final String UNFOLLOW =
            "DELETE FROM social.follow WHERE follower_id = ? AND followee_id = ?";

    private static final String IS_FOLLOWING =
            "SELECT EXISTS (SELECT 1 FROM social.follow WHERE follower_id = ? AND followee_id = ?)";

    /**
     * One grouped pass per relation instead of two correlated subqueries per video.
     * {@code = ANY(?)} takes the whole id list as a single array parameter, so the
     * SQL text is constant regardless of page size — no {@code IN (?,?,…)} built by
     * string concatenation, and no statement-cache churn.
     *
     * <p>The two relations are counted separately and merged in Java rather than
     * joined: joining likes to comments on video_id multiplies the rows and inflates
     * both counts.
     */
    private static final String LIKE_COUNTS_FOR = """
            SELECT video_id, count(*) AS c FROM social.video_like
            WHERE video_id = ANY(?) GROUP BY video_id
            """;

    private static final String COMMENT_COUNTS_FOR = """
            SELECT video_id, count(*) AS c FROM social.comment
            WHERE video_id = ANY(?) GROUP BY video_id
            """;

    private static final String FOLLOWED_AMONG = """
            SELECT followee_id FROM social.follow
            WHERE follower_id = ? AND followee_id = ANY(?)
            """;

    private static final String FOLLOWER_COUNT = "SELECT count(*) FROM social.follow WHERE followee_id = ?";

    private static final String FOLLOWING_COUNT = "SELECT count(*) FROM social.follow WHERE follower_id = ?";

    private final JdbcTemplate jdbc;

    SocialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void like(String videoId, String accountId) {
        jdbc.update(LIKE, UUID.fromString(videoId), UUID.fromString(accountId), Timestamp.from(Instant.now()));
    }

    void unlike(String videoId, String accountId) {
        jdbc.update(UNLIKE, UUID.fromString(videoId), UUID.fromString(accountId));
    }

    boolean isLiked(String videoId, String accountId) {
        Boolean liked = jdbc.queryForObject(
                IS_LIKED, Boolean.class, UUID.fromString(videoId), UUID.fromString(accountId));
        return Boolean.TRUE.equals(liked);
    }

    CommentView addComment(String videoId, String accountId, String body, String parentCommentId) {
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                ADD_COMMENT,
                commentId,
                UUID.fromString(videoId),
                UUID.fromString(accountId),
                body,
                Timestamp.from(now),
                parentCommentId == null ? null : UUID.fromString(parentCommentId));
        return new CommentView(commentId.toString(), videoId, accountId, body, now, parentCommentId, 0);
    }

    List<CommentView> listComments(String videoId) {
        return jdbc.query(
                LIST_COMMENTS,
                (rs, rowNum) -> new CommentView(
                        rs.getString("comment_id"),
                        rs.getString("video_id"),
                        rs.getString("account_id"),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant(),
                        null,
                        rs.getLong("reply_count")),
                UUID.fromString(videoId));
    }

    List<CommentView> listReplies(String commentId) {
        return jdbc.query(
                LIST_REPLIES,
                (rs, rowNum) -> new CommentView(
                        rs.getString("comment_id"),
                        rs.getString("video_id"),
                        rs.getString("account_id"),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("parent_comment_id"),
                        0),
                UUID.fromString(commentId));
    }

    /** A reply may only target a top-level comment on the same video -- no nested replies-of-replies. */
    boolean isTopLevelCommentOnVideo(String commentId, String videoId) {
        Boolean exists = jdbc.queryForObject(
                IS_TOP_LEVEL_COMMENT_ON_VIDEO, Boolean.class, UUID.fromString(commentId), UUID.fromString(videoId));
        return Boolean.TRUE.equals(exists);
    }

    SocialCounts countsFor(String videoId) {
        UUID id = UUID.fromString(videoId);
        return jdbc.queryForObject(
                COUNTS,
                (rs, rowNum) -> new SocialCounts(videoId, rs.getLong("like_count"), rs.getLong("comment_count")),
                id,
                id);
    }

    /** @return true if this created a new relationship (false if already following). */
    boolean follow(String followerId, String followeeId) {
        int rows = jdbc.update(FOLLOW, UUID.fromString(followerId), UUID.fromString(followeeId), Timestamp.from(Instant.now()));
        return rows > 0;
    }

    void unfollow(String followerId, String followeeId) {
        jdbc.update(UNFOLLOW, UUID.fromString(followerId), UUID.fromString(followeeId));
    }

    boolean isFollowing(String followerId, String followeeId) {
        Boolean following = jdbc.queryForObject(
                IS_FOLLOWING, Boolean.class, UUID.fromString(followerId), UUID.fromString(followeeId));
        return Boolean.TRUE.equals(following);
    }

    /** @return counts keyed by videoId, zero-filled for videos with no activity. */
    Map<String, SocialCounts> countsForAll(Collection<String> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        UUID[] ids = videoIds.stream().map(UUID::fromString).toArray(UUID[]::new);

        Map<String, Long> likes = countBy(LIKE_COUNTS_FOR, ids);
        Map<String, Long> comments = countBy(COMMENT_COUNTS_FOR, ids);

        Map<String, SocialCounts> counts = new HashMap<>();
        for (String videoId : videoIds) {
            counts.put(
                    videoId,
                    new SocialCounts(
                            videoId,
                            likes.getOrDefault(videoId, 0L),
                            comments.getOrDefault(videoId, 0L)));
        }
        return counts;
    }

    private Map<String, Long> countBy(String sql, UUID[] ids) {
        Map<String, Long> byVideo = new HashMap<>();
        jdbc.query(
                sql,
                rs -> {
                    byVideo.put(rs.getString("video_id"), rs.getLong("c"));
                },
                (Object) ids);
        return byVideo;
    }

    Set<String> followedAmong(String followerId, Collection<String> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Set.of();
        }
        UUID[] ids = creatorIds.stream().map(UUID::fromString).toArray(UUID[]::new);
        return new HashSet<>(
                jdbc.queryForList(FOLLOWED_AMONG, String.class, UUID.fromString(followerId), ids));
    }

    long followerCount(String accountId) {
        Long count = jdbc.queryForObject(FOLLOWER_COUNT, Long.class, UUID.fromString(accountId));
        return count == null ? 0 : count;
    }

    long followingCount(String accountId) {
        Long count = jdbc.queryForObject(FOLLOWING_COUNT, Long.class, UUID.fromString(accountId));
        return count == null ? 0 : count;
    }
}
