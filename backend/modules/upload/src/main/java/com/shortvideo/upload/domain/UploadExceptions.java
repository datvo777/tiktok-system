package com.shortvideo.upload.domain;

public final class UploadExceptions {

    public static class UploadNotFound extends RuntimeException {
        public UploadNotFound(String message) { super(message); }
    }

    public static class NotUploadOwner extends RuntimeException {
        public NotUploadOwner(String message) { super(message); }
    }

    public static class UploadExpired extends RuntimeException {
        public UploadExpired(String message) { super(message); }
    }

    public static class UploadObjectMissing extends RuntimeException {
        public UploadObjectMissing(String message) { super(message); }
    }

    public static class UploadSizeOutOfRange extends RuntimeException {
        public UploadSizeOutOfRange(String message) { super(message); }
    }

    private UploadExceptions() {}
}
