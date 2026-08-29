package com.shortvideo.upload.domain;

/** Canonical absolute-state payload (brief section 10, section 13). */
public final class UploadEvents {

    public record UploadCompleted(
            String uploadId, String videoId, String accountId, String sourceObjectKey, long sizeBytes) {}

    private UploadEvents() {}
}
