package com.shortvideo.moderation.api;

/** Creates the moderation record for an upload whose completion event never produced one. */
public interface ModerationBackfill {

    /**
     * Idempotent: does nothing, and returns false, if a record already exists.
     *
     * @return true if a PENDING record was created
     */
    boolean createPending(String videoId, String creatorId);
}
