package com.shortvideo.playback;

/**
 * Brief section 8 response table. No subclass carries media bytes or echoes
 * rejected input.
 */
public final class MediaAuthorizationException {

    /** Missing, expired, or mismatched cookies. */
    public static class Unauthorized extends RuntimeException {
        public Unauthorized(String message) { super(message); }
    }

    /** Confirmed revocation or ineligibility. */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) { super(message); }
    }

    /** Unable to establish safety because PostgreSQL is unavailable or inconclusive. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    /** Authorized request for a missing object. */
    public static class ObjectMissing extends RuntimeException {
        public ObjectMissing(String message) { super(message); }
    }

    private MediaAuthorizationException() {}
}
