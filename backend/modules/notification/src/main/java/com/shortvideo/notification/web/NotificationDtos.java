package com.shortvideo.notification.web;

import com.shortvideo.notification.domain.NotificationService;
import com.shortvideo.notification.domain.NotificationView;

public final class NotificationDtos {

    /**
     * @param unreadCount across the whole account, not just this page -- the
     *     badge previously counted only what the client had fetched.
     */
    public record NotificationListResponse(
            java.util.List<NotificationResponse> items, String nextCursor, long unreadCount) {
        public static NotificationListResponse from(NotificationService.NotificationPage page) {
            return new NotificationListResponse(
                    page.items().stream().map(NotificationResponse::from).toList(),
                    page.nextCursor(),
                    page.unreadCount());
        }
    }

    public record MarkAllReadResponse(int markedRead) {}

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
