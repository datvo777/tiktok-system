package com.shortvideo.video.api;

/** Starts transcoding for a video whose upload completed. */
public interface VideoProcessingDispatcher {

    /**
     * Idempotent: a video already past CREATED is left untouched.
     *
     * @return true if this call dispatched the transcode job
     */
    boolean dispatchProcessing(String videoId, String sourceObjectKey);
}
