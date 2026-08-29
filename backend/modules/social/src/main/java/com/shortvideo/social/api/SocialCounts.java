package com.shortvideo.social.api;

/** Brief section 15: feed scoring inputs (likeWeight, commentWeight). */
public record SocialCounts(String videoId, long likeCount, long commentCount) {}
