package com.shortvideo.video.api;

/** Brief section 7. Persisted as a string, never an ordinal. */
public enum ProcessingState {
    CREATED,
    UPLOADING,
    UPLOADED,
    TRANSCODING,
    READY,
    FAILED,
    EXPIRED
}
