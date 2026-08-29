package com.shortvideo.social.domain;

public record CreatorProfileView(
        String accountId, String displayName, String accountState, long followerCount, long followingCount) {}
