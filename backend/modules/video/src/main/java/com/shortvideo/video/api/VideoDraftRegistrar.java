package com.shortvideo.video.api;

/**
 * The Upload module's synchronous entry point into the Video module (brief section
 * 7.1). Called inside the Upload module's own transaction when creating an upload
 * session, so an immutable persisted owner exists before any byte is stored.
 */
public interface VideoDraftRegistrar {

    VideoDraft createDraft(String ownerAccountId);
}
