package com.shortvideo.social.domain;

import com.shortvideo.social.api.SocialCounts;
import java.sql.Timestamp;
import java.time.Instant;
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
            INSERT INTO social.comment (comment_id, video_id, account_id, body, created_at)
            VALUES (?, ?, ?, ?, ?)
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

    CommentView addComment(String videoId, String accountId, String body) {
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                ADD_COMMENT,
                commentId,
                UUID.fromString(videoId),
                UUID.fromString(accountId),
                body,
                Timestamp.from(now));
        return new CommentView(commentId.toString(), videoId, accountId, body, now);
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

    long followerCount(String accountId) {
        Long count = jdbc.queryForObject(FOLLOWER_COUNT, Long.class, UUID.fromString(accountId));
        return count == null ? 0 : count;
    }

    long followingCount(String accountId) {
        Long count = jdbc.queryForObject(FOLLOWING_COUNT, Long.class, UUID.fromString(accountId));
        return count == null ? 0 : count;
    }
}
