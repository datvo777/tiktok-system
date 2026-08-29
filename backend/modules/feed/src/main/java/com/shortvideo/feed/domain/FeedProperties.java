package com.shortvideo.feed.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Brief section 15: "Do not build machine-learning ranking first. Use a rule-based feed." */
@ConfigurationProperties(prefix = "shortvideo.feed")
public class FeedProperties {

    private int candidatePoolSize = 200;
    private int pageSize = 20;
    private Duration cacheTtl = Duration.ofSeconds(30);
    private double freshnessWeight = 10;
    private double likeWeight = 1;
    private double commentWeight = 2;
    private double followedCreatorBoost = 15;
    private double explorationWeight = 5;

    public int getCandidatePoolSize() { return candidatePoolSize; }
    public void setCandidatePoolSize(int candidatePoolSize) { this.candidatePoolSize = candidatePoolSize; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
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
}
