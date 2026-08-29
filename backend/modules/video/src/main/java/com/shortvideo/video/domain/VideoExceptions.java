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

    private VideoExceptions() {}
}
