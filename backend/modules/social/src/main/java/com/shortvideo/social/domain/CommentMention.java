package com.shortvideo.social.domain;

/**
 * One {@code @handle} resolved at comment-write time.
 *
 * <p>{@code handle} is the literal text matched in the comment body at the
 * moment it was posted, not the account's current handle — the two can
 * diverge later if the account renames. Keeping both lets a reader match this
 * mention back to its exact span in the (immutable) comment body without
 * re-resolving anything, while {@code accountId} is what a click should
 * actually navigate to.
 */
public record CommentMention(String handle, String accountId) {}
