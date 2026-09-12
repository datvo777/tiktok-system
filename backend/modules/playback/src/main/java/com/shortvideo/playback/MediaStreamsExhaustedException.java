package com.shortvideo.playback;

/**
 * The gateway is already streaming as many objects concurrently as it is
 * configured to. Raised before the response is committed so it can be answered as
 * a 503 with Retry-After, rather than as a truncated body behind a 200.
 */
public class MediaStreamsExhaustedException extends RuntimeException {

    public MediaStreamsExhaustedException(String message) {
        super(message);
    }
}
