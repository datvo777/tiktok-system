package com.shortvideo.notification.domain;

public final class NotificationExceptions {

    public static class NotificationNotFound extends RuntimeException {
        public NotificationNotFound(String message) { super(message); }
    }

    private NotificationExceptions() {}
}
