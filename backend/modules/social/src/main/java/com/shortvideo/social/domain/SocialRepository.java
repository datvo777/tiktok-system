package com.shortvideo.social.domain;

import com.shortvideo.social.api.SocialCounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final String SHARE = """
            INSERT INTO social.video_share (share_id, video_id, account_id, created_at)
            VALUES (?, ?, ?, ?)
            """;

    private static final String ADD_COMMENT = """
            INSERT INTO social.comment (comment_id, video_id, account_id, body, created_at, parent_comment_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /**
     * One page of top-level comments, newest first, each with the count of its
     * own live replies.
     *
     * <p>Keyset pagination on {@code (created_at, comment_id)} rather than
     * OFFSET: the list is ordered by a value that changes as people comment, so
     * an offset-based page 2 can skip or repeat rows that shifted underneath it.
     * The id breaks ties between comments posted in the same instant, which is
     * what makes the cursor total.
     *
     * <p>The previous query was a bare {@code LIMIT 200} with no cursor at all,
     * so the 201st comment on a video was simply unreachable and the client had
     * no way to know the list had been truncated.
     */
    private static final String LIST_COMMENTS = """
            SELECT c.comment_id, c.video_id, c.account_id, c.body, c.created_at,
                   (SELECT count(*) FROM social.comment r
                     WHERE r.parent_comment_id = c.comment_id AND r.deleted_at IS NULL) AS reply_count
            FROM social.comment c
            WHERE c.video_id = ? AND c.parent_comment_id IS NULL AND c.deleted_at IS NULL
              AND (?::timestamptz IS NULL OR (c.created_at, c.comment_id) < (?::timestamptz, ?::uuid))
            ORDER BY c.created_at DESC, c.comment_id DESC
            LIMIT ?
            """;

    private static final String LIST_REPLIES = """
            SELECT comment_id, video_id, account_id, body, created_at, parent_comment_id
            FROM social.comment
            WHERE parent_comment_id = ? AND deleted_at IS NULL
              AND (?::timestamptz IS NULL OR (created_at, comment_id) > (?::timestamptz, ?::uuid))
            ORDER BY created_at ASC, comment_id ASC
            LIMIT ?
            """;

    /** Author and video owner are the two principals allowed to delete a comment. */
    private static final String FIND_COMMENT_FOR_DELETE = """
            SELECT comment_id, video_id, account_id, parent_comment_id
            FROM social.comment
            WHERE comment_id = ? AND deleted_at IS NULL
            """;

    private static final String SOFT_DELETE_COMMENT = """
            UPDATE social.comment
            SET deleted_at = ?, deleted_by = ?
            WHERE comment_id = ? AND deleted_at IS NULL
            """;

    /**
     * Deleting a top-level comment takes its replies with it. Leaving them
     * behind would show answers to a question nobody can see any more, and the
     * rows are retained either way -- only their visibility changes.
     */
    private static final String SOFT_DELETE_REPLIES = """
            UPDATE social.comment
            SET deleted_at = ?, deleted_by = ?
            WHERE parent_comment_id = ? AND deleted_at IS NULL
            """;

    /**
     * Returns the author rather than a boolean: replying needs both the
     * validation ("is this a top-level comment on this video?") and the person
     * to notify, and one query answers both. An empty result means the comment
     * does not qualify.
     */
    private static final String TOP_LEVEL_COMMENT_AUTHOR = """
            SELECT account_id FROM social.comment
            WHERE comment_id = ? AND video_id = ? AND parent_comment_id IS NULL AND deleted_at IS NULL
            """;

    private static final String COUNTS = """
            SELECT
                (SELECT count(*) FROM social.video_like WHERE video_id = ?) AS like_count,
                (SELECT count(*) FROM social.comment WHERE video_id = ? AND deleted_at IS NULL) AS comment_count,
                (SELECT count(*) FROM social.video_share WHERE video_id = ?) AS share_count,
                (SELECT count(*) FROM social.video_view WHERE video_id = ?) AS view_count
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
            WHERE video_id = ANY(?) AND deleted_at IS NULL GROUP BY video_id
            """;

    private static final String SHARE_COUNTS_FOR = """
            SELECT video_id, count(*) AS c FROM social.video_share
            WHERE video_id = ANY(?) GROUP BY video_id
            """;

    private static final String FOLLOWED_AMONG = """
            SELECT followee_id FROM social.follow
            WHERE follower_id = ? AND followee_id = ANY(?)
            """;

    /**
     * Upsert: a rewatch bumps this viewer's row rather than adding one, and
     * {@code completed} latches true once they have watched it through, so a
     * later partial rewatch does not retract the fact.
     */
    private static final String RECORD_VIEW = """
            INSERT INTO social.video_view
                (video_id, account_id, play_count, total_watched_ms, completed, first_viewed_at, last_viewed_at)
            VALUES (?, ?, 1, ?, ?, ?, ?)
            ON CONFLICT (video_id, account_id) DO UPDATE SET
                play_count = social.video_view.play_count + 1,
                total_watched_ms = social.video_view.total_watched_ms + EXCLUDED.total_watched_ms,
                completed = social.video_view.completed OR EXCLUDED.completed,
                last_viewed_at = EXCLUDED.last_viewed_at
            """;

    /** Distinct viewers, not total plays: one person watching ten times is one view. */
    private static final String VIEW_COUNTS_FOR = """
            SELECT video_id, count(*) AS c FROM social.video_view
            WHERE video_id = ANY(?) GROUP BY video_id
            """;

    private static final String VIEW_COUNT = "SELECT count(*) FROM social.video_view WHERE video_id = ?";

    /** Which of these videos this viewer has already been shown. */
    private static final String VIEWED_AMONG = """
            SELECT video_id FROM social.video_view
            WHERE account_id = ? AND video_id = ANY(?)
            """;

    private static final String FOLLOWER_COUNT = "SELECT count(*) FROM social.follow WHERE followee_id = ?";

    private static final String FOLLOWING_COUNT = "SELECT count(*) FROM social.follow WHERE follower_id = ?";

    private final JdbcTemplate jdbc;

    SocialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when this call actually inserted a like, false when the row
     *     was already there. The statement is {@code ON CONFLICT DO NOTHING}, so
     *     the affected-row count distinguishes a first like from a repeat — which
     *     is what keeps a re-sent like from emitting a duplicate notification.
     */
    boolean like(String videoId, String accountId) {
        return jdbc.update(LIKE, UUID.fromString(videoId), UUID.fromString(accountId), Timestamp.from(Instant.now()))
                > 0;
    }

    void recordView(String videoId, String accountId, long watchedMs, boolean completed) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                RECORD_VIEW,
                UUID.fromString(videoId),
                UUID.fromString(accountId),
                Math.max(0, watchedMs),
                completed,
                now,
                now);
    }

    long viewCountFor(String videoId) {
        Long count = jdbc.queryForObject(VIEW_COUNT, Long.class, UUID.fromString(videoId));
        return count == null ? 0 : count;
    }

    Set<String> viewedAmong(String accountId, Collection<String> videoIds) {
        if (videoIds.isEmpty()) {
            return Set.of();
        }
        UUID[] ids = videoIds.stream().map(UUID::fromString).toArray(UUID[]::new);
        return new HashSet<>(jdbc.queryForList(VIEWED_AMONG, String.class, UUID.fromString(accountId), (Object) ids));
    }

    void unlike(String videoId, String accountId) {
        jdbc.update(UNLIKE, UUID.fromString(videoId), UUID.fromString(accountId));
    }

    boolean isLiked(String videoId, String accountId) {
        Boolean liked = jdbc.queryForObject(
                IS_LIKED, Boolean.class, UUID.fromString(videoId), UUID.fromString(accountId));
        return Boolean.TRUE.equals(liked);
    }

    void share(String videoId, String accountId) {
        jdbc.update(
                SHARE,
                UUID.randomUUID(),
                UUID.fromString(videoId),
                UUID.fromString(accountId),
                Timestamp.from(Instant.now()));
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

    /**
     * @param cursor where the previous page stopped, or null for the first page.
     * @param limit how many rows to return; the caller asks for one more than the
     *     page size so it can tell "there is more" from "that was everything"
     *     without a second count query.
     */
    List<CommentView> listComments(String videoId, CommentCursor cursor, int limit) {
        Timestamp createdAt = cursor == null ? null : Timestamp.from(cursor.createdAt());
        UUID commentId = cursor == null ? null : UUID.fromString(cursor.commentId());
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
                UUID.fromString(videoId),
                createdAt,
                createdAt,
                commentId,
                limit);
    }

    /** Where a page of comments stopped: the sort key, in full, so the next page is exact. */
    record CommentCursor(Instant createdAt, String commentId) {}

    /** What a delete needs to know before it is allowed: who wrote it, and on whose video. */
    record CommentOwnership(String commentId, String videoId, String accountId, String parentCommentId) {}

    Optional<CommentOwnership> findForDelete(String commentId) {
        return jdbc
                .query(
                        FIND_COMMENT_FOR_DELETE,
                        (rs, rowNum) -> new CommentOwnership(
                                rs.getString("comment_id"),
                                rs.getString("video_id"),
                                rs.getString("account_id"),
                                rs.getString("parent_comment_id")),
                        UUID.fromString(commentId))
                .stream()
                .findFirst();
    }

    /**
     * @return true when this call performed the delete, false when the row was
     *     already gone -- which makes a repeated delete idempotent rather than an
     *     error.
     */
    boolean softDeleteComment(String commentId, String actorAccountId, boolean cascadeToReplies) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID actor = UUID.fromString(actorAccountId);
        UUID id = UUID.fromString(commentId);
        if (cascadeToReplies) {
            jdbc.update(SOFT_DELETE_REPLIES, now, actor, id);
        }
        return jdbc.update(SOFT_DELETE_COMMENT, now, actor, id) > 0;
    }

    List<CommentView> listReplies(String commentId, CommentCursor cursor, int limit) {
        Timestamp createdAt = cursor == null ? null : Timestamp.from(cursor.createdAt());
        UUID cursorId = cursor == null ? null : UUID.fromString(cursor.commentId());
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
                UUID.fromString(commentId),
                createdAt,
                createdAt,
                cursorId,
                limit);
    }

    /** A reply may only target a top-level comment on the same video -- no nested replies-of-replies. */
    /** Empty when the comment does not exist, belongs to another video, or is itself a reply. */
    Optional<String> topLevelCommentAuthor(String commentId, String videoId) {
        return jdbc
                .query(
                        TOP_LEVEL_COMMENT_AUTHOR,
                        (rs, rowNum) -> rs.getString("account_id"),
                        UUID.fromString(commentId),
                        UUID.fromString(videoId))
                .stream()
                .findFirst();
    }

    SocialCounts countsFor(String videoId) {
        UUID id = UUID.fromString(videoId);
        return jdbc.queryForObject(
                COUNTS,
                (rs, rowNum) -> new SocialCounts(
                        videoId,
                        rs.getLong("like_count"),
                        rs.getLong("comment_count"),
                        rs.getLong("share_count"),
                        rs.getLong("view_count")),
                id,
                id,
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
        Map<String, Long> shares = countBy(SHARE_COUNTS_FOR, ids);
        Map<String, Long> views = countBy(VIEW_COUNTS_FOR, ids);

        Map<String, SocialCounts> counts = new HashMap<>();
        for (String videoId : videoIds) {
            counts.put(
                    videoId,
                    new SocialCounts(
                            videoId,
                            likes.getOrDefault(videoId, 0L),
                            comments.getOrDefault(videoId, 0L),
                            shares.getOrDefault(videoId, 0L),
                            views.getOrDefault(videoId, 0L)));
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
