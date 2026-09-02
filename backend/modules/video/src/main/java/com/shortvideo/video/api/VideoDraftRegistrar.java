package com.shortvideo.video.api;

/**
 * The Upload module's synchronous entry point into the Video module (brief section
 * 7.1). Called inside the Upload module's own transaction when creating an upload
 * session, so an immutable persisted owner exists before any byte is stored.
 */
public interface VideoDraftRegistrar {

    VideoDraft createDraft(String ownerAccountId, String title, String description);

    /**
     * Called by the Upload module's expired-session reaper when a draft's
     * upload never completed. A no-op if the draft has already moved past
     * CREATED (upload completed, or already expired) — safe to call more than
     * once.
     */
    void expireDraft(String videoId);
}
