package com.shortvideo.publication.domain;

public final class PublicationExceptions {

    public static class PublicationNotFound extends RuntimeException {
        public PublicationNotFound(String message) { super(message); }
    }

    public static class NotVideoOwner extends RuntimeException {
        public NotVideoOwner(String message) { super(message); }
    }

    /** The owner acted on a video an admin suspended; only moderation can lift that. */
    public static class PublicationSuspended extends RuntimeException {
        public PublicationSuspended(String message) { super(message); }
    }

    /** Too many publish/unpublish calls in the window; answered 429 with Retry-After. */
    public static class PublishRateLimited extends RuntimeException {
        private final java.time.Duration retryAfter;

        public PublishRateLimited(java.time.Duration retryAfter) {
            super("Too many publish changes");
            this.retryAfter = retryAfter;
        }

        public java.time.Duration retryAfter() { return retryAfter; }
    }

    private PublicationExceptions() {}
}
