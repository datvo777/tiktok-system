package com.shortvideo.moderation.domain;

/** Canonical absolute-state payload (brief section 10). */
public final class ModerationEvents {

    public record ModerationStateChanged(
            String videoId, String creatorId, ModerationState state, long aggregateVersion, String reason) {}

    private ModerationEvents() {}
}
