package com.shortvideo.social.domain;

import com.shortvideo.social.api.EngagementCounts;
import com.shortvideo.social.api.SocialCounts;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SocialRepository {

    private static final Logger log = LoggerFactory.getLogger(SocialRepository.class);

    /**
     * The ids of a batch lookup as uuids, leaving out any that are not one.
     *
     * <p>The ids a feed ranking asks about come from the eligibility projection, whose id columns
     * are VARCHAR(36) and so accept any short string. {@code UUID.fromString} on one of them throws,
     * and because the lookup serves a whole candidate pool, one malformed row in the projection would
     * fail the ranking for every viewer, signed in or not, until someone found and deleted it. An
     * id that is not a uuid cannot have a like, a follow or a view here, so leaving it out answers
     * the question correctly (none, zero) and costs the other 199 videos nothing.
     */
    static UUID[] parseUuids(Collection<String> ids) {
        List<UUID> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                parsed.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring an id that is not a uuid in a batch lookup: {}", id);
            }
        }
        return parsed.toArray(UUID[]::new);
    }

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
            INSERT INTO social.comment
                (comment_id, video_id, account_id, body, created_at, parent_comment_id,
                 mentioned_account_ids, mentioned_handles)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
                   c.mentioned_account_ids, c.mentioned_handles,
                   (SELECT count(*) FROM social.comment r
                     WHERE r.parent_comment_id = c.comment_id AND r.deleted_at IS NULL) AS reply_count
            FROM social.comment c
            WHERE c.video_id = ? AND c.parent_comment_id IS NULL AND c.deleted_at IS NULL
              AND (?::timestamptz IS NULL OR (c.created_at, c.comment_id) < (?::timestamptz, ?::uuid))
            ORDER BY c.created_at DESC, c.comment_id DESC
            LIMIT ?
            """;

    private static final String LIST_REPLIES = """
            SELECT comment_id, video_id, account_id, body, created_at, parent_comment_id,
                   mentioned_account_ids, mentioned_handles
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
     * The totals a feed ranking reads, kept per video by the engagement-count
     * consumer from the deltas the like and comment paths emit. A video with no
     * row has had no engagement.
     */
    private static final String ENGAGEMENT_FOR = """
            SELECT video_id, like_count, comment_count FROM social.video_engagement
            WHERE video_id = ANY(?)
            """;

    private static final String APPLY_ENGAGEMENT_DELTA = """
            INSERT INTO social.video_engagement (video_id, like_count, comment_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (video_id) DO UPDATE
            SET like_count = social.video_engagement.like_count + EXCLUDED.like_count,
                comment_count = social.video_engagement.comment_count + EXCLUDED.comment_count,
                updated_at = now()
            """;

    private static final String ENGAGEMENT_PAGE = """
            SELECT video_id FROM social.video_engagement
            WHERE video_id > ? ORDER BY video_id LIMIT ?
            """;

    /**
     * Recounts the given videos from the source tables and overwrites any stored total that
     * differs. A row touched after {@code ?} (the cutoff) is left alone: a delta may still be
     * in flight for it, and correcting now would count that delta twice.
     */
    private static final String RECONCILE_ENGAGEMENT = """
            UPDATE social.video_engagement e
            SET like_count = s.likes, comment_count = s.comments, updated_at = now()
            FROM (
                SELECT x.video_id,
                       (SELECT count(*) FROM social.video_like l WHERE l.video_id = x.video_id) AS likes,
                       (SELECT count(*) FROM social.comment c
                         WHERE c.video_id = x.video_id AND c.deleted_at IS NULL) AS comments
                FROM unnest(?::uuid[]) AS x(video_id)
            ) s
            WHERE e.video_id = s.video_id
              AND e.updated_at < ?
              AND (e.like_count <> s.likes OR e.comment_count <> s.comments)
            """;

    private static final String FOLLOWED_AMONG = """
            SELECT followee_id FROM social.follow
            WHERE follower_id = ? AND followee_id = ANY(?)
            """;

    private static final String FOLLOWED_CREATORS = """
            SELECT followee_id FROM social.follow
            WHERE follower_id = ?
            ORDER BY created_at DESC
            LIMIT ?
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
        UUID[] ids = parseUuids(videoIds);
        return new HashSet<>(jdbc.queryForList(VIEWED_AMONG, String.class, UUID.fromString(accountId), (Object) ids));
    }

    /** @return true when a like was removed, false when there was none (a repeated unlike). */
    boolean unlike(String videoId, String accountId) {
        return jdbc.update(UNLIKE, UUID.fromString(videoId), UUID.fromString(accountId)) > 0;
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

    CommentView addComment(
            String videoId, String accountId, String body, String parentCommentId, List<CommentMention> mentions) {
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now();
        UUID[] mentionedIds = mentions.stream().map(m -> UUID.fromString(m.accountId())).toArray(UUID[]::new);
        String[] mentionedHandles = mentions.stream().map(CommentMention::handle).toArray(String[]::new);
        jdbc.update(
                ADD_COMMENT,
                commentId,
                UUID.fromString(videoId),
                UUID.fromString(accountId),
                body,
                Timestamp.from(now),
                parentCommentId == null ? null : UUID.fromString(parentCommentId),
                mentionedIds,
                mentionedHandles);
        return new CommentView(commentId.toString(), videoId, accountId, body, now, parentCommentId, 0, mentions);
    }

    /**
     * Zips the two parallel columns back into pairs. Same order, same length --
     * both arrays are written from the same {@code List<CommentMention>} in
     * {@link #addComment} and a comment is never edited afterwards.
     */
    private static List<CommentMention> readMentions(ResultSet rs) throws SQLException {
        Array idArray = rs.getArray("mentioned_account_ids");
        Array handleArray = rs.getArray("mentioned_handles");
        if (idArray == null || handleArray == null) {
            return List.of();
        }
        Object[] ids = (Object[]) idArray.getArray();
        Object[] handles = (Object[]) handleArray.getArray();
        List<CommentMention> mentions = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            mentions.add(new CommentMention(handles[i].toString(), ids[i].toString()));
        }
        return mentions;
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
                        rs.getLong("reply_count"),
                        readMentions(rs)),
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
     * @return how many live comments this call hid: the comment itself plus, for a
     *     top-level one, its replies. Zero when the row was already gone, which
     *     makes a repeated delete idempotent rather than an error. The caller needs
     *     the number because the video's comment count falls by exactly that much.
     */
    int softDeleteComment(String commentId, String actorAccountId, boolean cascadeToReplies) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID actor = UUID.fromString(actorAccountId);
        UUID id = UUID.fromString(commentId);
        int hidden = 0;
        if (cascadeToReplies) {
            hidden += jdbc.update(SOFT_DELETE_REPLIES, now, actor, id);
        }
        return hidden + jdbc.update(SOFT_DELETE_COMMENT, now, actor, id);
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
                        0,
                        readMentions(rs)),
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

    /**
     * @return likes and comments keyed by videoId, zero-filled for videos with no activity.
     *     One primary-key lookup of the whole list. These are the maintained totals, so
     *     they trail a like or comment by the few seconds its event takes to be applied.
     */
    Map<String, EngagementCounts> engagementForAll(Collection<String> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        UUID[] ids = parseUuids(videoIds);

        Map<String, EngagementCounts> counts = new HashMap<>();
        jdbc.query(
                ENGAGEMENT_FOR,
                rs -> {
                    String videoId = rs.getString("video_id");
                    // Clamped: deltas commute, so a row can dip below zero for an instant
                    // when an unlike is applied before the like it undoes.
                    counts.put(
                            videoId,
                            new EngagementCounts(
                                    videoId,
                                    Math.max(0, rs.getLong("like_count")),
                                    Math.max(0, rs.getLong("comment_count"))));
                },
                (Object) ids);
        for (String videoId : videoIds) {
            counts.putIfAbsent(videoId, new EngagementCounts(videoId, 0, 0));
        }
        return counts;
    }

    void applyEngagementDelta(String videoId, int likeDelta, int commentDelta) {
        jdbc.update(APPLY_ENGAGEMENT_DELTA, UUID.fromString(videoId), likeDelta, commentDelta);
    }

    /** Video ids that have a stored total, in id order, strictly after {@code afterVideoId} (the start when null). */
    List<String> engagementPage(String afterVideoId, int limit) {
        UUID after = afterVideoId == null ? new UUID(0, 0) : UUID.fromString(afterVideoId);
        return jdbc.queryForList(ENGAGEMENT_PAGE, String.class, after, limit);
    }

    /** @return how many stored totals were wrong and have been corrected. */
    int reconcileEngagement(Collection<String> videoIds, Instant touchedBefore) {
        if (videoIds.isEmpty()) {
            return 0;
        }
        UUID[] ids = videoIds.stream().map(UUID::fromString).toArray(UUID[]::new);
        return jdbc.update(RECONCILE_ENGAGEMENT, ids, Timestamp.from(touchedBefore));
    }

    Set<String> followedAmong(String followerId, Collection<String> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Set.of();
        }
        UUID[] ids = parseUuids(creatorIds);
        return new HashSet<>(
                jdbc.queryForList(FOLLOWED_AMONG, String.class, UUID.fromString(followerId), ids));
    }

    Set<String> followedCreators(String followerId, int limit) {
        // LinkedHashSet keeps the newest-first order the query asked for.
        return new java.util.LinkedHashSet<>(
                jdbc.queryForList(FOLLOWED_CREATORS, String.class, UUID.fromString(followerId), limit));
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
