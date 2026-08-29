package com.shortvideo.upload.domain;

import java.time.Instant;

public record UploadView(
        String uploadId, String videoId, String status, Long completedSizeBytes, Instant expiresAt) {}
