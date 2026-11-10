package com.shortvideo.feed.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Brief section 15: "Do not build machine-learning ranking first. Use a rule-based feed." */
@ConfigurationProperties(prefix = "shortvideo.feed")
public class FeedProperties {

    private int candidatePoolSize = 200;
    private int pageSize = 20;
    private Duration cacheTtl = Duration.ofSeconds(30);

    /**
     * How long a signed-in viewer's ranking is kept for the pages that follow
     * page 0. {@link #cacheTtl} is far shorter than the time it takes to scroll
     * through a page, so with it alone every page was cut from a fresh ranking in
     * which the videos just watched had dropped to the "seen" tier -- shifting
     * the offsets and skipping the unseen videos in between. Page 0 starts a new
     * ranking; this is how long later pages may keep slicing it.
     */
    private Duration rankingSessionTtl = Duration.ofMinutes(30);

    private double freshnessWeight = 10;
    private double likeWeight = 1;
    private double commentWeight = 2;
    private double followedCreatorBoost = 15;
    private double explorationWeight = 5;

    /**
     * How often the exploration draw is reshuffled. The seed was
     * {@code viewerId.hashCode()} alone -- constant for the life of the account,
     * so a viewer's random tiebreak never changed and the ranking was frozen.
     * Mixing in a bucket of this length keeps a ranking stable across the pages of
     * one session while letting it differ between sessions.
     */
    private Duration explorationRotation = Duration.ofHours(6);

    public int getCandidatePoolSize() { return candidatePoolSize; }
    public void setCandidatePoolSize(int candidatePoolSize) { this.candidatePoolSize = candidatePoolSize; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    public Duration getRankingSessionTtl() { return rankingSessionTtl; }
    public void setRankingSessionTtl(Duration rankingSessionTtl) { this.rankingSessionTtl = rankingSessionTtl; }
    public double getFreshnessWeight() { return freshnessWeight; }
    public void setFreshnessWeight(double freshnessWeight) { this.freshnessWeight = freshnessWeight; }
    public double getLikeWeight() { return likeWeight; }
    public void setLikeWeight(double likeWeight) { this.likeWeight = likeWeight; }
    public double getCommentWeight() { return commentWeight; }
    public void setCommentWeight(double commentWeight) { this.commentWeight = commentWeight; }
    public double getFollowedCreatorBoost() { return followedCreatorBoost; }
    public void setFollowedCreatorBoost(double followedCreatorBoost) { this.followedCreatorBoost = followedCreatorBoost; }
    public double getExplorationWeight() { return explorationWeight; }
    public void setExplorationWeight(double explorationWeight) { this.explorationWeight = explorationWeight; }
    public Duration getExplorationRotation() { return explorationRotation; }
    public void setExplorationRotation(Duration explorationRotation) { this.explorationRotation = explorationRotation; }
}
