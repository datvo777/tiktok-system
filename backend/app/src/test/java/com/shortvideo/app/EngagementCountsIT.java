package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.social.api.EngagementCounts;
import com.shortvideo.social.api.EngagementReconciler;
import com.shortvideo.social.api.SocialDirectory;
import com.shortvideo.social.domain.EngagementCountListener;
import com.shortvideo.social.domain.SocialEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The like and comment counts a feed ranking scores on, against real PostgreSQL: how a delta
 * event moves the stored total, what the ranking reads back, and how the reconciliation sweep
 * repairs a total that has drifted from the source tables.
 */
class EngagementCountsIT extends AbstractDatabaseIT {

    @Autowired SocialDirectory social;
    @Autowired EngagementCountListener listener;
    @Autowired EngagementReconciler reconciler;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deltaEventsMoveTheTotalsAndAVideoNoOneTouchedReadsAsZero() throws Exception {
        String busy = UUID.randomUUID().toString();
        String quiet = UUID.randomUUID().toString();

        deliver(busy, 1, 0);
        deliver(busy, 1, 0);
        deliver(busy, 1, 0);
        deliver(busy, 0, 1);
        deliver(busy, 0, 1);
        deliver(busy, 0, 1);
        deliver(busy, -1, -1); // an unlike and a deleted comment

        Map<String, EngagementCounts> counts = social.engagementForAll(List.of(busy, quiet));

        assertThat(counts.get(busy)).isEqualTo(new EngagementCounts(busy, 2, 2));
        assertThat(counts.get(quiet)).isEqualTo(new EngagementCounts(quiet, 0, 0));
    }

    @Test
    void aRedeliveredEventIsAppliedOnce() throws Exception {
        String video = UUID.randomUUID().toString();
        String event = event(UUID.randomUUID(), video, 1, 0);

        listener.onSocialEvent(event);
        listener.onSocialEvent(event);

        assertThat(social.engagementForAll(List.of(video)).get(video).likeCount()).isEqualTo(1);
    }

    @Test
    void anUnlikeThatArrivesBeforeItsLikeNeverShowsANegativeCountAndEndsAtZero() throws Exception {
        String video = UUID.randomUUID().toString();

        deliver(video, -1, 0);
        assertThat(social.engagementForAll(List.of(video)).get(video).likeCount()).isZero();

        deliver(video, 1, 0);
        assertThat(social.engagementForAll(List.of(video)).get(video).likeCount()).isZero();
    }

    @Test
    void noVideosIsNoQueryAndNoCounts() {
        assertThat(social.engagementForAll(List.of())).isEmpty();
    }

    @Test
    void theSweepCorrectsADriftedTotalButLeavesOneAnEventJustTouched() {
        String drifted = UUID.randomUUID().toString();
        String justTouched = UUID.randomUUID().toString();
        String correct = UUID.randomUUID().toString();
        for (String video : List.of(drifted, justTouched, correct)) {
            like(video);
            like(video);
            comment(video, false);
            comment(video, true); // deleted: not part of the count
        }
        // Stored totals: wrong and long untouched; wrong but touched a moment ago; right.
        stored(drifted, 7, 5, "1 hour");
        stored(justTouched, 7, 5, "0 seconds");
        stored(correct, 2, 1, "1 hour");

        EngagementReconciler.Sweep sweep = reconciler.reconcile(null, 100000, Duration.ofMinutes(10));

        assertThat(sweep.corrected()).isGreaterThanOrEqualTo(1);
        assertThat(totals(drifted)).containsExactly(2L, 1L);
        assertThat(totals(justTouched)).containsExactly(7L, 5L);
        assertThat(totals(correct)).containsExactly(2L, 1L);
    }

    private void deliver(String videoId, int likeDelta, int commentDelta) throws Exception {
        listener.onSocialEvent(event(UUID.randomUUID(), videoId, likeDelta, commentDelta));
    }

    private String event(UUID eventId, String videoId, int likeDelta, int commentDelta) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                eventId,
                EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED,
                1,
                AggregateTypes.SOCIAL,
                UUID.randomUUID().toString(),
                1L,
                Instant.now(),
                "short-video-backend",
                "social",
                "corr",
                null,
                new SocialEvents.EngagementChanged(videoId, likeDelta, commentDelta)));
    }

    private void stored(String videoId, long likes, long comments, String age) {
        jdbc.update(
                "INSERT INTO social.video_engagement (video_id, like_count, comment_count, updated_at)"
                        + " VALUES (?::uuid, ?, ?, now() - ?::interval)",
                videoId,
                likes,
                comments,
                age);
    }

    private List<Long> totals(String videoId) {
        return jdbc.queryForObject(
                "SELECT ARRAY[like_count, comment_count] FROM social.video_engagement WHERE video_id = ?::uuid",
                (rs, n) -> List.of(((Long[]) rs.getArray(1).getArray())[0], ((Long[]) rs.getArray(1).getArray())[1]),
                videoId);
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
