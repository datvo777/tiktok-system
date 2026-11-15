package com.shortvideo.social.api;

/**
 * The two counts brief section 15 scores a video on (likeWeight, commentWeight).
 * Shares and views are display-only, so a ranking asks for these instead of the
 * full {@link SocialCounts} and does not pay for the two queries behind them.
 */
public record EngagementCounts(String videoId, long likeCount, long commentCount) {}
