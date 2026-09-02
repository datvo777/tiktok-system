package com.shortvideo.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.social.api.SocialCounts;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class FeedScorerTest {

    private final FeedProperties properties = new FeedProperties();
    private final FeedScorer scorer = new FeedScorer(properties);

    @Test
    void freshVideoScoresNearFullFreshnessWeight() {
        VideoEligibilityView video = eligibleVideo(Instant.now());
        double score = scorer.score(video, counts(0, 0), false, 0);

        assertThat(score).isCloseTo(properties.getFreshnessWeight(), within(0.5));
    }

    @Test
    void oldVideoScoresNearZeroFreshness() {
        VideoEligibilityView video = eligibleVideo(Instant.now().minus(30, ChronoUnit.DAYS));
        double score = scorer.score(video, counts(0, 0), false, 0);

        assertThat(score).isLessThan(0.5);
    }

    @Test
    void likesAndCommentsAddToTheScore() {
        Instant now = Instant.now();
        double base = scorer.score(eligibleVideo(now), counts(0, 0), false, 0);
        double withEngagement = scorer.score(eligibleVideo(now), counts(10, 5), false, 0);

        double expectedDelta = 10 * properties.getLikeWeight() + 5 * properties.getCommentWeight();
        assertThat(withEngagement - base).isCloseTo(expectedDelta, within(0.01));
    }

    @Test
    void followingTheCreatorAddsTheBoostExactly() {
        Instant now = Instant.now();
        double notFollowed = scorer.score(eligibleVideo(now), counts(0, 0), false, 0);
        double followed = scorer.score(eligibleVideo(now), counts(0, 0), true, 0);

        assertThat(followed - notFollowed).isCloseTo(properties.getFollowedCreatorBoost(), within(0.01));
    }

    @Test
    void explorationSampleIsScaledByExplorationWeight() {
        Instant now = Instant.now();
        double noExploration = scorer.score(eligibleVideo(now), counts(0, 0), false, 0);
        double fullExploration = scorer.score(eligibleVideo(now), counts(0, 0), false, 1);

        assertThat(fullExploration - noExploration).isCloseTo(properties.getExplorationWeight(), within(0.01));
    }

    private VideoEligibilityView eligibleVideo(Instant updatedAt) {
        return new VideoEligibilityView(
                "video-1", "creator-1", "Test title", null, "READY", 1, "DURABLE", "APPROVED", "PUBLISHED", true,
                "ACTIVE", "CLEAR", true, 1, 1, 1, 1, updatedAt);
    }

    private SocialCounts counts(long likes, long comments) {
        return new SocialCounts("video-1", likes, comments);
    }
}
