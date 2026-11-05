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

    /**
     * Reinstates a video whose appeal was approved, if it is still under the rejection the appeal was
     * filed against. Idempotent.
     *
     * @param moderationVersion the moderation version of that rejection, as recorded on the appeal
     * @return true if the video was reinstated now; false if it was no longer under that rejection
     */
    boolean reinstateFromAppeal(String videoId, long moderationVersion);
}
