package com.shortvideo.feed.web;

public final class FeedExceptions {

    /**
     * The video does not exist, is not currently eligible, or is revoked. All
     * three answer the same way on purpose: whether a particular id exists but is
     * being withheld is not something an arbitrary caller should be able to learn
     * from a status code.
     */
    public static class FeedItemNotFound extends RuntimeException {
        public FeedItemNotFound(String message) {
            super(message);
        }
    }

    private FeedExceptions() {}
}
