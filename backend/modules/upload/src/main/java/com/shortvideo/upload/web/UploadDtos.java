package com.shortvideo.upload.web;

import com.shortvideo.upload.domain.UploadSessionCreated;
import com.shortvideo.upload.domain.UploadView;
import java.time.Instant;

public final class UploadDtos {

    public record CreateUploadResponse(String uploadId, String videoId, String uploadUrl, Instant expiresAt) {
        public static CreateUploadResponse from(UploadSessionCreated created) {
            return new CreateUploadResponse(
                    created.uploadId(), created.videoId(), created.uploadUrl(), created.expiresAt());
        }
    }

    public record UploadResponse(String uploadId, String videoId, String status, Long completedSizeBytes) {
        public static UploadResponse from(UploadView view) {
            return new UploadResponse(view.uploadId(), view.videoId(), view.status(), view.completedSizeBytes());
        }
    }

    private UploadDtos() {}
}
