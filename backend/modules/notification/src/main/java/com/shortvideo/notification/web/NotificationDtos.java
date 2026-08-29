package com.shortvideo.notification.web;

import com.shortvideo.notification.domain.NotificationView;

public final class NotificationDtos {

    public record NotificationResponse(
            String notificationId, String type, String message, String relatedVideoId, boolean read, String createdAt) {
        public static NotificationResponse from(NotificationView view) {
            return new NotificationResponse(
                    view.notificationId(),
                    view.type(),
                    view.message(),
                    view.relatedVideoId(),
                    view.read(),
                    view.createdAt().toString());
        }
    }

    private NotificationDtos() {}
}
