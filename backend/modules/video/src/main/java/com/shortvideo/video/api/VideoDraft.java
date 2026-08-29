package com.shortvideo.video.api;

import java.time.Instant;

/** What the Upload module gets back from {@link VideoDraftRegistrar}. */
public record VideoDraft(String videoId, String ownerAccountId, long aggregateVersion, Instant createdAt) {}
