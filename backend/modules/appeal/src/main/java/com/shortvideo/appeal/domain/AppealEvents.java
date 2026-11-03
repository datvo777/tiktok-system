package com.shortvideo.appeal.domain;

/** Canonical absolute-state payloads (brief section 10, Rule 11). */
public final class AppealEvents {

    /** The appeal's state after a submission or a decision; {@code reason} is that act's reason. */
    public record AppealStateChanged(
            String videoId, String creatorId, String state, long aggregateVersion, String reason) {}

    private AppealEvents() {}
}
