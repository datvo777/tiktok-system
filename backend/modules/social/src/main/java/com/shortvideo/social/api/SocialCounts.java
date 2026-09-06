package com.shortvideo.social.api;

/** Brief section 15: feed scoring inputs (likeWeight, commentWeight). shareCount is display-only, not scored. */
public record SocialCounts(String videoId, long likeCount, long commentCount, long shareCount) {}
