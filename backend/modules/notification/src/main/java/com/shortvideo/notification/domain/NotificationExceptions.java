package com.shortvideo.notification.domain;

public final class NotificationExceptions {

    /** A page cursor that this service did not issue. */
    public static class InvalidCursor extends RuntimeException {
        public InvalidCursor(String message) { super(message); }
    }

    public static class NotificationNotFound extends RuntimeException {
        public NotificationNotFound(String message) { super(message); }
    }

    private NotificationExceptions() {}
}
