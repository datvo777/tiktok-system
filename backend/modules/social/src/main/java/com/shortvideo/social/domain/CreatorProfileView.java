package com.shortvideo.social.domain;

/**
 * A publicly visible creator profile. Account state is deliberately absent: only
 * eligible creators have a profile at all, so exposing the field could only ever
 * disclose which accounts have been suspended.
 *
 * <p>{@code following} is viewer-relative -- whether the caller follows this
 * creator -- not part of the creator's own state.
 */
public record CreatorProfileView(
        String accountId,
        String displayName,
        /** The unique username; never null. */
        String handle,
        /** Null when the creator has not written one. */
        String bio,
        long followerCount,
        long followingCount,
        boolean following) {}
