package com.shortvideo.upload.domain;

/** Persisted as a string, never an ordinal (brief section 7). */
public enum UploadStatus {
    PENDING,
    COMPLETED,
    EXPIRED
}
