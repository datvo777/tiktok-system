package com.shortvideo.social.api;

/**
 * Brief section 15: feed scoring inputs (likeWeight, commentWeight).
 * {@code shareCount} is display-only, not scored.
 *
 * <p>{@code viewCount} is distinct viewers, not total plays -- one person
 * watching ten times is one view, which is what makes it comparable between
 * videos.
 */
public record SocialCounts(String videoId, long likeCount, long commentCount, long shareCount, long viewCount) {}
