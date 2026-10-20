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

    private PublicationExceptions() {}
}
