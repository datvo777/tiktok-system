package com.shortvideo.appeal.domain;

/** Canonical absolute-state payloads (brief section 10, Rule 11). */
public final class AppealEvents {

    public record AppealDecided(
            String videoId, String creatorId, String state, long aggregateVersion, String reason) {}

    private AppealEvents() {}
}
