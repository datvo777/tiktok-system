package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.social.api.EngagementCounts;
import com.shortvideo.social.api.SocialDirectory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The like and comment counts a feed ranking scores on, against real PostgreSQL:
 * the grouped queries over an array of ids, and what they leave out.
 */
class EngagementCountsIT extends AbstractDatabaseIT {

    @Autowired SocialDirectory social;
    @Autowired JdbcTemplate jdbc;

    @Test
    void countsLikesAndLiveCommentsPerVideoAndZeroFillsTheRest() {
        String busy = UUID.randomUUID().toString();
        String quiet = UUID.randomUUID().toString();
        like(busy);
        like(busy);
        like(busy);
        comment(busy, false);
        comment(busy, false);
        comment(busy, true); // deleted: must not score

        Map<String, EngagementCounts> counts = social.engagementForAll(List.of(busy, quiet));

        assertThat(counts.get(busy)).isEqualTo(new EngagementCounts(busy, 3, 2));
        assertThat(counts.get(quiet)).isEqualTo(new EngagementCounts(quiet, 0, 0));
    }

    @Test
    void noVideosIsNoQueryAndNoCounts() {
        assertThat(social.engagementForAll(List.of())).isEmpty();
    }

    private void like(String videoId) {
        jdbc.update(
                "INSERT INTO social.video_like (video_id, account_id, created_at) VALUES (?::uuid, ?::uuid, now())",
                videoId,
                UUID.randomUUID().toString());
    }

    private void comment(String videoId, boolean deleted) {
        jdbc.update(
                "INSERT INTO social.comment (comment_id, video_id, account_id, body, created_at, deleted_at)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, 'hi', now(), "
                        + (deleted ? "now()" : "NULL")
                        + ")",
                UUID.randomUUID().toString(),
                videoId,
                UUID.randomUUID().toString());
    }
}
