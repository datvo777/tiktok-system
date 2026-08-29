package com.shortvideo.shared.security;

import java.time.Instant;

/**
 * The verified claims of a playback cookie (brief section 8). {@code mode} is
 * {@code OWNER_PREVIEW} or {@code PUBLIC}.
 */
public record PlaybackClaims(
        String sessionId, String viewerId, String videoId, int processingVersion, String mode, Instant expiresAt) {}
