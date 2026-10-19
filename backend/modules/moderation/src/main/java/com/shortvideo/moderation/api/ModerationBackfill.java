package com.shortvideo.moderation.api;

/** Creates the moderation record for an upload whose completion event never produced one. */
public interface ModerationBackfill {

    /**
     * Idempotent: does nothing, and returns false, if a record already exists.
     *
     * @return true if a PENDING record was created
     */
    boolean createPending(String videoId, String creatorId);

    /**
     * Puts a held record into the review queue because its owner has asked to publish it. Idempotent.
     *
     * @return true if it was queued now; false if it was already queued or already decided
     * @throws IllegalStateException if there is no moderation record for the video yet
     */
    boolean requestReview(String videoId);
}
