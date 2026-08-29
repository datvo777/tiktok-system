package com.shortvideo.notification.domain;

import java.time.Instant;

public record NotificationView(
        String notificationId, String type, String message, String relatedVideoId, boolean read, Instant createdAt) {}
