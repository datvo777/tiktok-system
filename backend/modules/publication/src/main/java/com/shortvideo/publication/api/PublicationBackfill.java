package com.shortvideo.publication.api;

/** Lets a reconciler repair a video whose publication draft was never created. */
public interface PublicationBackfill {

    /**
     * Creates the PRIVATE draft if there is none. Idempotent.
     *
     * @param processingReady whether the video is already READY, since the event that would
     *     have said so cannot be replayed
     * @return true if a draft was created
     */
    boolean backfillDraft(String videoId, String ownerAccountId, boolean processingReady);
}
