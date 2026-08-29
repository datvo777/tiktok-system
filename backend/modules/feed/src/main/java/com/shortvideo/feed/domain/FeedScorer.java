package com.shortvideo.feed.domain;

import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.social.api.SocialCounts;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Brief section 15: {@code score = freshnessWeight + likeWeight + commentWeight +
 * followedCreatorBoost + randomExploration}. The exploration sample is passed in
 * rather than drawn here, so the deterministic part of the score stays a pure,
 * directly unit-testable function.
 */
@Component
class FeedScorer {

    private final FeedProperties properties;

    FeedScorer(FeedProperties properties) {
        this.properties = properties;
    }

    double score(VideoEligibilityView video, SocialCounts counts, boolean followedCreator, double explorationSample) {
        double freshness = freshnessScore(video.updatedAt());
        double social = counts.likeCount() * properties.getLikeWeight() + counts.commentCount() * properties.getCommentWeight();
        double followBoost = followedCreator ? properties.getFollowedCreatorBoost() : 0;
        double exploration = explorationSample * properties.getExplorationWeight();
        return freshness + social + followBoost + exploration;
    }

    /** Exponential decay from freshnessWeight toward 0 over roughly a day. */
    private double freshnessScore(Instant updatedAt) {
        long ageSeconds = Math.max(0, Duration.between(updatedAt, Instant.now()).getSeconds());
        double decay = Math.exp(-ageSeconds / (24.0 * 3600));
        return properties.getFreshnessWeight() * decay;
    }
}
