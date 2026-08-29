package com.shortvideo.notification.domain;

/** Canonical absolute-state payload (brief section 10, Rule 11). */
public final class NotificationEvents {

    public record NotificationCreated(
            String notificationId, String recipientAccountId, String type, String message, String relatedVideoId) {}

    private NotificationEvents() {}
}
