package com.shortvideo.video.domain;

public final class VideoExceptions {

    public static class VideoNotFound extends RuntimeException {
        public VideoNotFound(String message) { super(message); }
    }

    public static class NotVideoOwner extends RuntimeException {
        public NotVideoOwner(String message) { super(message); }
    }

    public static class VideoNotReady extends RuntimeException {
        public VideoNotReady(String message) { super(message); }
    }

    /**
     * The object store could not be asked (unreachable, timed out, refused), as opposed to
     * answering that an object is not there. Whatever was being decided has not been decided.
     */
    public static class AssetStoreUnavailable extends RuntimeException {
        public AssetStoreUnavailable(String message, Throwable cause) { super(message, cause); }
    }

    private VideoExceptions() {}
}
