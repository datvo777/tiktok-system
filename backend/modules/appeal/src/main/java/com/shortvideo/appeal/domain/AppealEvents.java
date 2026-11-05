package com.shortvideo.appeal.domain;

/** Canonical absolute-state payloads (brief section 10, Rule 11). */
public final class AppealEvents {

    /**
     * The appeal's state after a submission or a decision; {@code reason} is that act's reason.
     * {@code moderationVersion} is the version of the rejection the appeal was filed against, so a
     * consumer reversing it can refuse to touch a later rejection.
     */
    public record AppealStateChanged(
            String videoId,
            String creatorId,
            String state,
            long aggregateVersion,
            long moderationVersion,
            String reason) {}

    private AppealEvents() {}
}
