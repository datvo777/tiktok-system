package com.shortvideo.social.domain;

public final class SocialExceptions {

    public static class VideoNotEligible extends RuntimeException {
        public VideoNotEligible(String message) { super(message); }
    }

    public static class CreatorNotFound extends RuntimeException {
        public CreatorNotFound(String message) { super(message); }
    }

    public static class CannotFollowSelf extends RuntimeException {
        public CannotFollowSelf(String message) { super(message); }
    }

    public static class CommentNotFound extends RuntimeException {
        public CommentNotFound(String message) { super(message); }
    }

    /** A page cursor that this service did not issue. */
    public static class InvalidCursor extends RuntimeException {
        public InvalidCursor(String message) { super(message); }
    }

    public static class CollectionNotFound extends RuntimeException {
        public CollectionNotFound(String message) { super(message); }
    }

    /** Unique per owner, case-insensitively: two "Watch later" folders cannot be told apart. */
    public static class CollectionNameTaken extends RuntimeException {
        public CollectionNameTaken(String message) { super(message); }
    }

    public static class InvalidCollectionName extends RuntimeException {
        public InvalidCollectionName(String message) { super(message); }
    }

    /** Per-owner ceiling on collections or on videos within one -- not a quota on the account. */
    public static class CollectionLimitReached extends RuntimeException {
        public CollectionLimitReached(String message) { super(message); }
    }

    /** Too many comments or replies from one account in the window; answered 429 with Retry-After. */
    /** A like, follow, share or view past the per-account limit. */
    public static class WriteRateLimited extends RuntimeException {
        private final java.time.Duration retryAfter;

        public WriteRateLimited(java.time.Duration retryAfter) {
            super("Too many requests");
            this.retryAfter = retryAfter;
        }

        public java.time.Duration retryAfter() { return retryAfter; }
    }

    public static class CommentRateLimited extends RuntimeException {
        private final java.time.Duration retryAfter;

        public CommentRateLimited(java.time.Duration retryAfter) {
            super("Too many comments");
            this.retryAfter = retryAfter;
        }

        public java.time.Duration retryAfter() { return retryAfter; }
    }

    private SocialExceptions() {}
}
