package com.shortvideo.social.domain;

/**
 * A publicly visible creator profile. Account state is deliberately absent: only
 * eligible creators have a profile at all, so exposing the field could only ever
 * disclose which accounts have been suspended.
 */
public record CreatorProfileView(
        String accountId, String displayName, long followerCount, long followingCount) {}
