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

    /**
     * Applies what the sources say now, for a video whose processing or moderation event was
     * dead-lettered after its draft existed (nothing replays a DLT). Safe next to live events:
     * moderation goes through the same version guard as the event, so an older decision is ignored,
     * and a processing outcome is only ever applied when it says READY.
     *
     * @param processingReady whether the video is READY now; false changes nothing
     * @param moderationState the moderation record's state; PENDING (or null) changes nothing
     * @param moderationVersion the moderation record's aggregate version
     * @return true if the publication state changed
     */
    boolean reconcileFlags(String videoId, boolean processingReady, String moderationState, long moderationVersion);
}
