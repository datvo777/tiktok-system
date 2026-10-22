package com.shortvideo.moderation.domain;

import java.time.Instant;

/** {@code createdAt} is when the upload arrived; {@code queuedAt} is when it started waiting for a person. */
public record ModerationView(
        String videoId,
        String creatorId,
        ModerationState state,
        Instant createdAt,
        Instant queuedAt,
        QueueReason queueReason) {}
