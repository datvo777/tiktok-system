package com.shortvideo.upload.web;

import com.shortvideo.upload.domain.UploadSessionCreated;
import com.shortvideo.upload.domain.UploadView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public final class UploadDtos {

    /**
     * Captured at upload time (brief section 7.1); description is optional, title is not.
     * Both are immutable afterwards. Text is NFC-normalised and trimmed, and control or
     * invisible format characters are rejected (a description may contain line breaks and tabs).
     */
    public record CreateUploadRequest(
            @Schema(description = "Required. Max 150 characters; no control characters.", maxLength = 150)
                    @NotBlank @Size(max = 150) String title,
            @Schema(description = "Optional. Max 2000 characters; line breaks and tabs allowed.", maxLength = 2000)
                    @Size(max = 2000) String description) {}

    /**
     * {@code formFields} must be posted as form parts before the file part; they
     * carry the signed policy that caps the upload size at {@code maxBytes}.
     */
    public record CreateUploadResponse(
            String uploadId,
            String videoId,
            String uploadUrl,
            Map<String, String> formFields,
            long maxBytes,
            Instant expiresAt) {

        public static CreateUploadResponse from(UploadSessionCreated created) {
            return new CreateUploadResponse(
                    created.uploadId(),
                    created.videoId(),
                    created.uploadUrl(),
                    created.formFields(),
                    created.maxBytes(),
                    created.expiresAt());
        }
    }

    public record UploadResponse(String uploadId, String videoId, String status, Long completedSizeBytes) {
        public static UploadResponse from(UploadView view) {
            return new UploadResponse(view.uploadId(), view.videoId(), view.status(), view.completedSizeBytes());
        }
    }

    private UploadDtos() {}
}
