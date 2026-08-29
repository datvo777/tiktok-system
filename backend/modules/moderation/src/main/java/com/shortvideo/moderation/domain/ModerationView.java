package com.shortvideo.moderation.domain;

import java.time.Instant;

public record ModerationView(String videoId, String creatorId, ModerationState state, Instant createdAt) {}
